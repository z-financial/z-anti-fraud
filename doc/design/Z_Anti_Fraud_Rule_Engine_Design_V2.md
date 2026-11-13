# Z-Anti-Fraud Rule Engine Design V2

The consolidated V2.0 design defines the sync API, final component interfaces, and startup configuration lifecycle. [End-to-end V2 design](Z_Anti_Fraud_End_to_End_Design_V2.md)

## 1. Scope

The Rule Engine evaluates a typed current event and historical feature values against developer-authored Drools rules. Developers write native DRL and configure thresholds in generic JSON parameter maps. A release can update DRL, parameters, or feature definitions by activating a validated bundle while the service is running.

The Risk Router selects rule IDs for the incoming business event. The Feature Service owns historical queries. The Rule Engine validates inputs for the selected rules, executes runnable rules, and returns per-rule findings. The Decision Engine applies the existing advisory decision policy to those findings. The Risk Gateway owns the request deadline. Temporal event sequences, persisted Drools sessions, and generated DRL are outside V2 scope. [Risk Router design](Z_Anti_Fraud_Risk_Router_Design_V2.md) · [Feature Service design](Z_Anti_Fraud_Feature_Service_Design_V2.md)

## 2. Feature references and current-event facts

Rules use qualified feature references in the form `groupId.featureId`. For example, `transfer-out-24h.transfer-out-count-24h` references the `transfer-out-count-24h` feature in the `transfer-out-24h` group. The Feature Service uses globally unique feature IDs. The evaluation bundle holds the resolved mapping between these two forms.

At publication, resolve each reference against actual group membership in the pinned feature snapshot. Reject ambiguous references and validate the feature's result type against the rule declaration. Moving `A.feature1` to group B invalidates `A.feature1`, even when the global ID `feature1` still exists. Reject the candidate bundle with `FEATURE_NOT_FOUND`, identifying the dependent rule and its original reference. The developer updates the dependent rules to `B.feature1` and republishes. The previous bundle remains active throughout. Changes to a group's source, window, or shared filters can affect a feature under the same qualified reference. Run the dependent rules' regression cases for those changes.

The Risk Router selects rules from the decoded `EventModel` before requesting features. The DRL request fact uses the same business `eventType`. For a transfer, that is `EventType.TRANSFER`. `TRANSFER_OUT` is an `activityType` used in historical feature filters. A `TransferRequestFact` exposes the current amount and account from the decoded business event. History uses one `asOf` and excludes the current upstream `(source, eventId)` with all of its activities.

## 3. Rule authoring and parameters

Each DRL rule name is its stable application rule ID. The compiler reads custom metadata from the compiled rule. `@eventType` declares the supported business event type and is checked against the route at publication. `@requiredFeatures` declares a comma-separated list of qualified references and expected Java value types. An empty string declares a rule that uses current-event data alone. In the example, `LONG` denotes `java.lang.Long`.

The executor supplies an immutable `RuleInput` for each runnable rule. It contains that rule's validated features and parameters and exposes typed accessors. Each DRL rule binds the `RuleInput` with its own rule ID. The request fact and the result collector are local to the session. The fact types expose JavaBean getters for DRL property constraints.

### Example DRL

```drl
package com.example.zantifraud.rules

import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.evaluation.rule.runtime.RuleInput;
import com.example.zantifraud.evaluation.rule.runtime.RuleHitCollector;
import com.example.zantifraud.evaluation.rule.runtime.TransferRequestFact;

global RuleHitCollector hits;

rule "transfer-out-amount-24h-limit"
    @eventType("TRANSFER")
    @requiredFeatures("transfer-out-24h.transfer-out-amount-24h:LONG")
when
    $input : RuleInput(ruleId == "transfer-out-amount-24h-limit")
    $transfer : TransferRequestFact(eventType == EventType.TRANSFER)
    eval($input.getLongFeature("transfer-out-24h.transfer-out-amount-24h")
         > $input.getLongParameter("limit") - $transfer.getAmountMinor())
then
    hits.recordMatch($input.getRuleId(), "TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED");
end
```

The comparison preserves the existing rolling amount rule. It compares the history to the allowance remaining after the current transfer and avoids adding two potentially large amounts. Equality at the limit produces `NOT_MATCHED`.

### Parameter contract and values

The versioned rule contract declares parameter names, types, and ranges. For the example rule, its parameter contract is the following map.

```json
{
  "limit": { "type": "LONG", "minimum": 1 }
}
```

The separately versioned values file for that rule is below.

```json
{
  "ruleId": "transfer-out-amount-24h-limit",
  "parameters": {
    "limit": 500000
  }
}
```

The loader normalizes JSON values to the declared Java types. Integral types require exact conversion and range checks. Reject missing, unknown, or invalid parameter values at publication. Rule authors can add parameters by updating the contract, values, and DRL. A value-only update can reuse the compiled `KieBase`. Optional scores, if a rule emits them, are informational under the V2 decision policy.

Compilation validates DRL syntax and Java calls. The publication validator checks declared references and parameters. Per-rule tests exercise getters across rule branches, since a valid DRL file can still contain a getter call for an undeclared input. The rule-specific input exposes only declared values, so a rule cannot accidentally consume an undeclared feature fetched for another rule.

## 4. Request evaluation

1. The coordinator captures one immutable evaluation snapshot at request start. The Risk Router selects rule IDs from that snapshot using the typed business event.
2. Take the union of the selected rules' resolved feature IDs. If it is empty, proceed directly to rule evaluation. Otherwise, fetch those features against the bundle's exact feature configuration. Set the feature-query deadline to leave time for rule execution within the request budget.
3. Validate the returned feature values and errors per selected rule. A missing value, feature error, wrong type, or successful `null` for a required value makes that rule `UNAVAILABLE`. A present zero is a valid input. A missing qualified reference is a configuration error with code `FEATURE_NOT_FOUND`. If the feature call fails entirely or returns an unexpected version, mark the requested history unavailable and proceed with rules that use the current event alone.
4. Build rule-specific input facts for the remaining applicable rules, including those that only use current-event data. Run them in a fresh request-local `KieSession` created from the bundle's compiled `KieBase`. Use a request-local collector, call `fireAllRules()`, and dispose of the session in `finally`. Rules with unavailable inputs receive no input fact. If no rule is runnable, skip session creation.
5. Assemble one outcome per applicable rule and return it to the Decision Engine. Record the bundle, parameter and feature configuration versions and the request `asOf` in evaluation metadata.

The exact Java request and result types, how the pinned configuration is supplied, and where results are adapted into rule inputs remain open for alignment across the three designs. [Open evaluation interface](Z_Anti_Fraud_Risk_Router_Design_V2.md#open-item-for-future-alignment)

### Outcome contract

| Rule outcome | Meaning |
| --- | --- |
| `MATCHED` | The runnable rule emitted a finding with its stable rule ID, reason code, and optional score. |
| `NOT_MATCHED` | The rule was runnable, Drools completed, and the rule emitted no finding. |
| `UNAVAILABLE` | A required input was missing or invalid, or a rule execution failed. Include the applicable reason code and keep failure details in evaluation diagnostics. |

If the session fails or its deadline expires, treat that run's results as unavailable. Only a completed session supplies matches and non-matches. Preserve pre-execution input errors in the evaluation diagnostics. Return an empty outcome list when zero rules apply.

The [existing Decision Engine policy](../risk-api-v1.md#decision-policy) consumes these outcomes. Any match yields `COMPLETED` / `BLOCK`, including when a different rule is unavailable. If no rule matches and a rule is unavailable, or zero rules apply, the result is `UNAVAILABLE` with no recommendation. If all applicable rules complete and none matches, the result is `COMPLETED` / `ALLOW`. Evaluation metadata is available for tracing. The existing risk API retains its response contract.

## 5. Publication and rollback

A bundle contains its version, the routing snapshot, the compiled `KieBase`, rule descriptors, validated parameter values and version, and the pinned immutable feature configuration. Publishing any of these inputs creates a candidate bundle.

1. Compile changed DRL into a candidate `KieBase` using the pinned Drools version. Validate unique rule IDs, event type metadata, qualified feature references, expected types, and parameter contracts and values.
2. Run the candidate's rule and integration tests. Test applicable event routing, threshold boundaries, unavailable dependencies, and compatibility with the pinned Feature Service configuration. Feature or parameter changes can reuse the compiled `KieBase` when DRL stays the same.
3. Activate the complete bundle with one atomic reference swap. If validation fails, keep the active bundle. Retain the previous valid bundle for rollback. In-flight requests finish using the bundle they captured, including its feature snapshot and parameters.

Each service instance activates a bundle after loading and validating its artifacts. Instances can briefly serve different versions during a rollout. Record the versions used for each evaluation and use the same activation path for rollback.

DRL publication follows the review and authorization process for executable application code. Rules use request facts, historical features, and parameters to emit findings. Keep I/O and business mutations outside rule code. Bound evaluation concurrency and use the remaining request deadline. Record rule execution and feature-query latency, publication failures, active versions, hits, and unavailable outcomes.

## 6. Transfer migration and acceptance

Port the three existing transfer rules independently. Preserve their IDs, thresholds, amount representation, and reason codes. Historical counts and amounts use the existing 24-hour group. Both history rules require a nonnegative `Long`. The count feature uses `COUNT` with `distinct: ["operationId"]`. Upstream guarantees one qualifying transfer submission per `operationId` and reuses `(source, eventId)` on retry. After excluding that event's activities from history, the count rule adds the current transaction once. The two historical features can be fetched in one SQL statement.

| Rule | Historical dependency | Match condition |
| --- | --- | --- |
| `transfer-out-amount-24h-limit` | `transfer-out-24h.transfer-out-amount-24h` | Historical amount plus current amount exceeds the limit. |
| `transfer-out-count-24h-limit` | `transfer-out-24h.transfer-out-count-24h` | Historical distinct count plus one exceeds the limit. Implement as `history >= limit` to avoid overflow. |
| `transfer-single-amount-limit` | None | Current amount exceeds the limit. |

Verify the following before cutover.

- Compile and run the exact DRL examples using the pinned Drools dependency on Java 17. Check metadata extraction, typed fact binding, and outcome collection.
- Compare the three DRL rules with the existing Java rules. Cover equal-to-limit and above-limit cases, empty history, and all matching reason codes.
- Replay a transfer with multiple outgoing legs. The history excludes the entire current event, and the count rule adds one for the current transaction.
- Load a compiled rule outside the selected route. Only selected rules with usable inputs receive a `RuleInput` and produce findings.
- Simulate a failed history query and an oversized current transfer. The two history rules are `UNAVAILABLE`, the single-transfer rule is `MATCHED`, and the Decision Engine returns `BLOCK`. Repeat below the single-transfer limit and confirm `UNAVAILABLE` with no recommendation.
- Simulate a valid zero, a successful `null` aggregate, a feature error, and a wrong Java type. Verify that only the rules with unavailable or invalid required inputs are skipped.
- Move a referenced feature from group A to B. Publication fails with `FEATURE_NOT_FOUND` until the dependent rules reference the new group. A change to another group's feature has no effect on the reference.
- Activate or roll back during feature retrieval. Verify that the DRL, parameters, features, and reported versions all belong to the bundle captured at request start.

## References

- [V1 risk API and transfer rules](../risk-api-v1.md#transfer-rules)
- [Drools traditional DRL reference](https://kie.apache.org/docs/10.0.x/drools/drools/language-reference-traditional/index.html)
- [Drools KIE bases and sessions](https://kie.apache.org/docs/10.0.x/drools/drools/rule-engine/index.html#kie-sessions)

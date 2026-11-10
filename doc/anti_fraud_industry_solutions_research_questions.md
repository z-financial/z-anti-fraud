# Industrial Solutions for the Anti-Fraud Research Questions

**Main research question:**  
How can a dedicated anti-fraud architecture support effective and efficient fraud detection in modern fintech transaction systems?

**Research note:**  
This document records current industry approaches relevant to the five sub-research questions. It focuses on concrete engineering patterns described by major payment providers, fintech vendors, and large-scale technology companies. The examples are illustrative rather than exhaustive, and vendor claims should be treated as implementation evidence rather than independent performance validation.

---

## RQ1 — Real-Time Detection

**Sub-question:**  
How can an anti-fraud system provide effective fraud detection while maintaining low latency for synchronous transaction processing?

### Short description

A synchronous fraud check sits directly on the critical transaction path. Industry systems therefore try to avoid expensive database scans, sequential cross-service calls, and heavy feature computation during each request. Common approaches include dedicated low-latency feature services, continuously precomputed aggregations, parallel feature retrieval, in-memory rules, and risk scoring before authorization.

### Solutions

#### 1. Dedicated low-latency counter / feature services

- **Description:**  
  Maintain frequently used velocity features—such as failed payments in the last hour or transactions from the same user/device—in a specialized service optimized for high-QPS reads. This avoids recomputing historical aggregates from operational databases during every fraud check. Grab's Counter Service is a concrete large-scale example.

- **Reference:**  
  [Grab Engineering — Migrating Counter Service storage: Design choices and learnings (2026)](https://engineering.grab.com/counter-service-storage-migration)

- **Short original quote:**  
  > “The service handles tens of thousands of queries per second (QPS) with about a billion requests per day.”

#### 2. Streaming / precomputed aggregations

- **Description:**  
  Compute historical windows continuously in the background and expose the resulting features directly to the synchronous decision path. This changes work such as “count transactions in the previous 12 hours” from a live database aggregation into a fast lookup.

- **Reference:**  
  [Sardine — Product Updates: July 2026](https://www.sardine.ai/blog/product-updates-july-2026)

- **Short original quote:**  
  > “Issuing risk rules now draw on streaming aggregations, computed continuously in the background rather than calculated live on every check.”

#### 3. Parallel feature aggregation

- **Description:**  
  Use a dedicated feature-aggregation layer that fetches current state from operational services and historical/precomputed state from feature stores in parallel. The decision engine waits for the assembled feature set rather than sequentially contacting every dependency. Uber's Mastermind architecture uses this approach.

- **Reference:**  
  [Uber Engineering — Mastermind: Using Uber Engineering to Combat Fraud in Real Time](https://www.uber.com/us/en/blog/mastermind/)

- **Short original quote:**  
  > “The feature aggregation service fetches real-time features from different services and pre-computed features from the data store in parallel.”

#### 4. Inline ML risk classification with configurable thresholds

- **Description:**  
  Run a trained model on each incoming payment and map the score/classification to an allow, block, review, or challenge decision. The expensive model-training process stays offline, while inference is optimized for the synchronous path. Thresholds let operators trade fraud capture against false declines and latency-sensitive customer friction.

- **Reference:**  
  [Adyen Docs — Machine learning rules](https://docs.adyen.com/risk-management/configure-your-risk-profile/machine-learning-rules/)

- **Short original quote:**  
  > “Machine learning assesses the properties of each incoming payment request and gives the transaction a risk classification.”

---

## RQ2 — Adaptability

**Sub-question:**  
How can the system rapidly adapt to changing fraud patterns and detection requirements without tightly coupling fraud logic to core business services?

### Short description

Fraud patterns change faster than normal product release cycles. A common industry response is to move fraud policy out of core application code and into dedicated rule, workflow, feature, and configuration platforms. Fraud analysts and data scientists can then change detection logic without redeploying transaction services.

### Solutions

#### 1. Self-service rule engines with dynamic reload

- **Description:**  
  Store rules as data/configuration rather than hard-coded application logic. Analysts modify rules through a portal, while the fraud engine detects the change and reloads the new rules. This separates fraud-policy evolution from releases of payment, order, wallet, or account services.

- **Reference:**  
  [Grab Engineering — Griffin, an Anti-fraud Risk Rule Engine Making Billions of Predictions Daily](https://engineering.grab.com/griffin)

- **Short original quote:**  
  > “Griffin Rule Engine reloads rules immediately as long as it detects any rule changes.”

#### 2. Backtesting, shadow mode, and A/B testing before rollout

- **Description:**  
  New detection logic should be evaluated on historical data and/or live traffic without immediately affecting customers. Backtesting estimates false positives and fraud capture; shadow mode observes live behavior safely; A/B testing compares alternative strategies before full deployment.

- **Reference:**  
  [Sardine — Fraud Rules Engine for Real-Time Fraud and AML Decisions](https://www.sardine.ai/rules-engine)

- **Short original quote:**  
  > “Backtest rules on historical data, run them in shadow mode on live traffic, and A/B test different strategies before rollout.”

#### 3. Declarative, self-service feature engineering

- **Description:**  
  Detection changes often require new features, not just new rules. PayPal describes a configuration-driven feature platform in which data scientists define *what* a feature means while the platform handles real-time, near-real-time, or batch execution and registration in a feature store. This reduces dependence on backend engineers for every feature change.

- **Reference:**  
  [PayPal Developer Blog — Declarative Feature Engineering at PayPal](https://developer.paypal.com/community/blog/declarative-feature-engineering-at-paypal/)

- **Short original quote:**  
  > “Data scientists can write a declaration of what their features look like rather than explicitly specify how to construct them.”

#### 4. Human-friendly rule authoring plus historical validation

- **Description:**  
  Modern commercial platforms increasingly expose rule-building interfaces to fraud teams instead of requiring application-code changes. Stripe Radar, for example, combines customizable rules with historical backtesting and an assistant that can translate natural-language intent into rule syntax.

- **Reference:**  
  [Stripe — Radar: Rules 101](https://stripe.com/guides/radar-rules-101)

- **Short original quote:**  
  > “You can also backtest the rule directly from Radar Assistant.”

---

## RQ3 — Continuous Evolution

**Sub-question:**  
How can asynchronous analysis and feedback mechanisms enable the anti-fraud system to continuously improve its detection capabilities?

### Short description

Fraud labels often arrive after the original transaction through chargebacks, manual reviews, account investigations, refunds, or confirmed abuse. Industry platforms therefore maintain an asynchronous learning loop: collect outcomes, link them back to earlier decisions, analyze emerging patterns, retrain or recalibrate models, and generate or refine future rules.

### Solutions

#### 1. Feed confirmed outcomes back into adaptive models

- **Description:**  
  Fraud decisions should not end when the synchronous request finishes. Later information—such as a payment being marked fraudulent—can become training or calibration feedback. Stripe explicitly describes Radar as continuously learning from transaction patterns and user-provided fraud feedback.

- **Reference:**  
  [Stripe Docs — Radar risk evaluation](https://docs.stripe.com/radar/risk-evaluation)

- **Short original quote:**  
  > “Our AI model is flexible and responsive, continuously learns from new customer purchase patterns and transaction features.”

#### 2. Explicit decision / label feedback APIs

- **Description:**  
  Record what ultimately happened to a user, order, transaction, or session and feed those outcomes back into the fraud platform. Sift's Decisions API represents actions such as approving, blocking, or banning and uses those records to improve future risk scores.

- **Reference:**  
  [Sift Developers — Decisions API Overview](https://developers.sift.com/docs/curl/decisions-api/overview/)

- **Short original quote:**  
  > “Sift uses this information to continuously improve the accuracy of your risk scores.”

#### 3. Human-in-the-loop anomaly detection and automatic rule generation

- **Description:**  
  Offline or near-real-time analytics can look for emerging fraud patterns that synchronous models do not yet recognize. Uber's RADAR system detects anomalous fraud segments, generates candidate protection rules, and sends them to analysts for review before production deployment.

- **Reference:**  
  [Uber Engineering — Project RADAR: Intelligent Early Fraud Detection System with Humans in the Loop](https://www.uber.com/us/en/blog/project-radar-intelligent-early-fraud-detection/)

- **Short original quote:**  
  > “RADAR monitors fine-grained segments of Uber’s marketplace, detects the start of a fraud attack, and generates a rule to stop it.”

#### 4. Unsupervised anomaly detection for previously unseen fraud

- **Description:**  
  Supervised models depend on known labels and may miss a newly invented modus operandi. Grab has used unsupervised graph anomaly detection to identify unusual consumer, merchant, and interaction structures without requiring labels for the new fraud pattern first.

- **Reference:**  
  [Grab Engineering — Unsupervised graph anomaly detection: Catching new fraudulent behaviours](https://engineering.grab.com/graph-anomaly-model)

- **Short original quote:**  
  > “We needed a machine learning model that is able to detect them quickly without the need for any label supervision.”

---

## RQ4 — Multi-Strategy Detection

**Sub-question:**  
How can rules, machine-learning models, and graph-based analysis be coordinated to detect different types of fraudulent behaviour?

### Short description

No single detection method is sufficient. Rules are fast, controllable, and explainable; supervised ML captures nonlinear patterns at scale; anomaly detection helps with unknown behaviors; and graph analysis detects relationships, collusion, shared devices, and fraud rings. Mature systems therefore orchestrate several detectors and combine their outputs into a final decision.

### Solutions

#### 1. ML scores used as inputs to a rule-based decision engine

- **Description:**  
  A common architecture is to calculate ML risk scores first and then expose those scores as features to an explainable rule engine. Uber's Mastermind fetches features, invokes one or more ML models, adds the returned risk scores to the feature set, and then evaluates rules/actions.

- **Reference:**  
  [Uber Engineering — Mastermind: Using Uber Engineering to Combat Fraud in Real Time](https://www.uber.com/us/en/blog/mastermind/)

- **Short original quote:**  
  > “The machine learning service runs one or more models and returns risk scores.”

#### 2. Graph analytics and GNNs alongside rules and conventional features

- **Description:**  
  Graph systems model users, devices, IPs, merchants, accounts, and transactions as connected entities. They are particularly useful for collusion and fraud rings that look normal when each transaction is evaluated independently. Grab describes graph databases, network-derived features, graph visualization, and GNN models as additions to its broader fraud platform.

- **Reference:**  
  [Grab Engineering — Graph Networks: Striking fraud syndicates in the dark](https://engineering.grab.com/graph-networks)

- **Short original quote:**  
  > “Graph Neural Networks (GNN) are able to utilise the structural correlations on the graph.”

#### 3. Unified rules, ML, device intelligence, and connection graph

- **Description:**  
  Commercial platforms increasingly expose multiple detection technologies behind one orchestration layer. Sardine combines rules/workflows, ML features and models, device/behavior signals, and a connections graph so that the same decision workflow can use signals from several detection strategies.

- **Reference:**  
  [Sardine — Agentic Financial Crime Platform](https://www.sardine.ai/)

- **Short original quote:**  
  > “Act instantly using rules, workflows, and ML models.”

#### 4. ML + custom rules + manual review

- **Description:**  
  A practical hybrid architecture often keeps humans in the loop for ambiguous cases. Stripe Radar combines machine-learning risk evaluation, business-defined rules, and manual review so low-risk transactions can pass automatically, high-risk transactions can be blocked, and uncertain cases can be investigated.

- **Reference:**  
  [Stripe — New Radar features help you better manage fraud](https://stripe.com/blog/new-radar-features)

- **Short original quote:**  
  > “Radar’s machine learning, rules, and manual reviews are working in tandem to block or allow payment attempts.”

---

## RQ5 — Context-Aware and Non-Invasive Detection

**Sub-question:**  
How can an anti-fraud system use contextual information, such as user authentication activity, transaction history, and wallet state, to detect fraud without introducing intrusive changes or tight coupling within core financial services?

### Short description

Context-aware fraud detection needs data owned by many systems, but embedding fraud-specific code into every financial service creates long-term coupling. Industry solutions usually centralize risk decisioning and collect context through APIs, events, SDKs, feature stores, or dedicated data-orchestration services. Core services expose facts or events; the anti-fraud platform owns the fraud interpretation.

### Solutions

#### 1. Event/API-based behavioral context ingestion

- **Description:**  
  Instead of putting fraud rules inside login, order, or payment services, those systems emit standardized events to a dedicated fraud platform. Sift accepts lifecycle events such as logins and transactions plus custom events/fields, then calculates risk centrally. This preserves business-service ownership of raw facts while the risk system owns fraud semantics.

- **Reference:**  
  [Sift Developers — API Overview and Events API](https://developers.sift.com/docs/curl/decisions-api/overview/)

- **Short original quote:**  
  > “Use the Events API to record the core actions users take in your application.”

#### 2. Standalone risk API that can sit beside an existing payment stack

- **Description:**  
  A dedicated risk platform can expose a scoring API rather than becoming the transaction processor itself. The financial application sends the necessary transaction/account context and receives a risk result. Stripe now explicitly supports Radar with non-Stripe payment processing, illustrating the “sidecar risk service” pattern.

- **Reference:**  
  [Stripe — Radar](https://stripe.com/radar)

- **Short original quote:**  
  > “Plug Radar into your existing setup—no need to change your processor.”

#### 3. Central data-orchestration / feature-aggregation layer

- **Description:**  
  Put cross-service data acquisition behind a dedicated anti-fraud component. Core systems expose generic APIs or data feeds; the orchestrator retrieves booking/transaction history, balance or account state, device information, and precomputed features, then provides one normalized feature set to the decision engine. Grab's Griffin/Data Orchestrator and Uber's Mastermind feature service both follow this pattern.

- **Reference:**  
  [Grab Engineering — Griffin](https://engineering.grab.com/griffin)

- **Short original quote:**  
  > “Data Orchestration: a service that collects all data needed for predictions.”

#### 4. Dedicated asynchronous decision/feedback stream

- **Description:**  
  Not every contextual signal has to be gathered synchronously. Decision traces, outcomes, and operational events can be published to a stream and consumed asynchronously by the anti-fraud platform for analytics, feedback, and model training. Grab's Archivist uses Kafka as a shared side channel for rule/ML prediction events rather than coupling every consumer directly to the producing service.

- **Reference:**  
  [Grab Engineering — Building a Hyper Self-Service, Distributed Tracing and Feedback System for Rule & ML Predictions](https://engineering.grab.com/building-hyper-self-service-distributed-tracing-feedback-system)

- **Short original quote:**  
  > “Each service writes its prediction events to a Kafka stream.”

---

## Cross-Question Industry Pattern

Across these implementations, a common architecture appears:

1. **Keep the synchronous path small:** use cached/precomputed features, low-latency counters, parallel retrieval, and optimized inference.
2. **Centralize decisioning:** place fraud rules and detector orchestration in a dedicated risk service instead of scattering rules across transaction services.
3. **Separate data acquisition from fraud logic:** use event streams, APIs, SDKs, feature stores, and data orchestrators to obtain context.
4. **Make policy configurable:** allow analysts/data scientists to update rules, thresholds, and features independently from core-service deployments.
5. **Combine detectors:** rules provide explicit control, ML provides probabilistic scoring, and graph/anomaly methods capture relational or previously unseen behavior.
6. **Build an asynchronous learning loop:** feed chargebacks, manual-review outcomes, confirmed fraud, and anomaly analysis back into rules and models.
7. **Validate before enforcement:** use backtesting, shadow traffic, simulations, A/B tests, and staged rollout to control false positives and operational risk.

This pattern strongly supports the idea of a **dedicated, non-invasive anti-fraud architecture**: the financial system continues to own transactions and state, while the anti-fraud platform owns feature aggregation, detection logic, risk decisioning, feedback, and investigation.

---

## Sources Consulted

- Grab Engineering — Counter Service storage migration (2026)
- Grab Engineering — Griffin anti-fraud rule engine
- Grab Engineering — Graph Networks / graph-based fraud detection
- Grab Engineering — Unsupervised graph anomaly detection
- Grab Engineering — Archivist prediction tracing and feedback
- Uber Engineering — Mastermind
- Uber Engineering — Project RADAR
- Stripe Radar product documentation and engineering/product guides
- Sift developer documentation
- Adyen Protect / machine-learning risk documentation
- PayPal developer engineering blog on declarative feature engineering
- Sardine product and engineering documentation, including 2026 product updates
- Unit21 transaction-monitoring documentation (used during landscape review)

_Last updated: 7 October 2026._

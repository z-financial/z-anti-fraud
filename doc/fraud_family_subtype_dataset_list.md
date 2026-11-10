# Fraud Type Taxonomy

## 1. Money Laundering — Structuring / Fragmentation

**Fraud family:** Money Laundering — Structuring / Fragmentation  
**Short description:** Illicit funds are split into smaller or repeated transactions to reduce visibility, avoid thresholds, or disguise the original amount.

| Subtype | Short description | Dataset |
|---|---|---|
| Structuring | A large amount is deliberately divided into smaller, organized transactions to avoid monitoring or reporting thresholds. | SAML-D |
| Smurfing | Illicit funds are broken into many smaller transactions, potentially involving multiple accounts or participants. | SAML-D |
| High-Frequency Small Transactions | Many relatively small transactions occur within a short time window, creating a suspicious fragmentation/velocity pattern. | Amaretto |

---

## 2. Money Laundering — Fan-In / Consolidation

**Fraud family:** Money Laundering — Fan-In / Consolidation  
**Short description:** Funds from multiple accounts or transaction paths are consolidated into one account or destination.

| Subtype | Short description | Dataset |
|---|---|---|
| Fan-In | Many source accounts transfer funds into one central account. | SAML-D, IBM AML |
| Layered Fan-In | Funds pass through intermediate accounts or layers before being consolidated into a final account. | SAML-D |

---

## 3. Money Laundering — Fan-Out / Distribution

**Fraud family:** Money Laundering — Fan-Out / Distribution  
**Short description:** Funds from one source are distributed across multiple accounts, often to create layers or use mule/intermediary accounts.

| Subtype | Short description | Dataset |
|---|---|---|
| Fan-Out | One source account distributes funds to many destination accounts. | SAML-D, IBM AML |
| Layered Fan-Out | Funds pass through one or more intermediary layers before being distributed to multiple destinations. | SAML-D |

---

## 4. Money Laundering — Scatter / Gather Networks

**Fraud family:** Money Laundering — Scatter / Gather Networks  
**Short description:** Funds are split and recombined, or consolidated and redistributed, to obscure the relationship between their source and destination.

| Subtype | Short description | Dataset |
|---|---|---|
| Scatter-Gather | Funds are split across several accounts and later recombined into another account. | SAML-D, IBM AML |
| Gather-Scatter | Funds from several accounts are first consolidated and then redistributed to multiple destinations. | SAML-D, IBM AML |

---

## 5. Money Laundering — Circular Movement

**Fraud family:** Money Laundering — Circular Movement  
**Short description:** Funds move through a chain of accounts and eventually return to an earlier or originating account, creating artificial transaction layers.

| Subtype | Short description | Dataset |
|---|---|---|
| Cycle | Funds travel through multiple accounts and return to a previous account, forming a transaction cycle. | SAML-D, IBM AML |

---

## 6. Money Laundering — Bipartite / Layered Networks

**Fraud family:** Money Laundering — Bipartite / Layered Networks  
**Short description:** Groups of accounts exchange funds through structured many-to-many or multi-layer transaction networks.

| Subtype | Short description | Dataset |
|---|---|---|
| Bipartite | Transactions occur between two groups of accounts in a many-to-many structure. | SAML-D, IBM AML |
| Stacked Bipartite | Multiple bipartite layers are connected sequentially, creating a deeper laundering network. | SAML-D |
| Stack | Funds move through several sequential layers or groups of accounts. | IBM AML |

---

## 7. Money Laundering — Irregular Network Movement

**Fraud family:** Money Laundering — Irregular Network Movement  
**Short description:** Suspicious funds move through a network without following a simple predefined graph structure such as a cycle or fan pattern.

| Subtype | Short description | Dataset |
|---|---|---|
| Random | Funds move among suspicious accounts using a relatively irregular network topology. | IBM AML |

---

## 8. Money Laundering — Rapid Pass-Through

**Fraud family:** Money Laundering — Rapid Pass-Through  
**Short description:** An account receives funds and forwards them soon afterward, behaving primarily as an intermediary or mule account.

| Subtype | Short description | Dataset |
|---|---|---|
| Deposit-Send | Funds are deposited into an account and subsequently sent to another beneficiary, often after a short delay. | SAML-D |
| Rapid Large Collateral Movement | Large collateral enters and exits an account within a short period. | Amaretto |

---

## 9. Money Laundering — Cash / Large Outflow

**Fraud family:** Money Laundering — Cash / Large Outflow  
**Short description:** Suspicious activity involves unusually large outward movement of money or assets, including cash withdrawal or a single large transfer.

| Subtype | Short description | Dataset |
|---|---|---|
| Cash Withdrawal | Suspicious funds are withdrawn as cash as part of the laundering process. | SAML-D |
| Single Large Transaction | A single unusually large transaction is used to place or move illicit funds. | SAML-D |
| Large Asset Withdrawal | A large asset withdrawal strongly deviates from the account's previous behaviour. | Amaretto |

---

## 10. Money Laundering — Behavioural Change

**Fraud family:** Money Laundering — Behavioural Change  
**Short description:** An account suddenly behaves differently from its historical transaction pattern.

| Subtype | Short description | Dataset |
|---|---|---|
| Behavioural Change 1 | A generated scenario in which current activity suddenly deviates from historical behaviour. | SAML-D |
| Behavioural Change 2 | A second distinct generated behavioural-change scenario. | SAML-D |
| Unusual-Time Trading | A transaction occurs at a time that is abnormal relative to expected trading behaviour. | Amaretto |
| Rounded Transaction Amounts | Repeated unusually rounded amounts create a pattern that differs from normal trading behaviour. | Amaretto |

---

## 11. Trade-Based Money Laundering

**Fraud family:** Trade-Based Money Laundering  
**Short description:** Legitimate-looking trade or commercial transactions are manipulated to transfer illicit value.

| Subtype | Short description | Dataset |
|---|---|---|
| Over-Invoicing | The stated value of goods or services is deliberately inflated so that extra value can be transferred under a legitimate-looking invoice. | SAML-D |

---

## 12. Account Takeover / Account Draining

**Fraud family:** Account Takeover / Account Draining  
**Short description:** An attacker gains control of a legitimate account and moves or withdraws its funds.

| Subtype | Short description | Dataset |
|---|---|---|
| Fraudulent Transfer | Money is transferred from a compromised account to another account controlled or used by the attacker. | PaySim |
| Fraudulent Cash-Out | Funds from a compromised account are withdrawn or cashed out of the system. | PaySim |

---

## 13. Credit-Card Transaction Fraud

**Fraud family:** Credit-Card Transaction Fraud  
**Short description:** A card transaction is labelled fraudulent, but the dataset does not provide an official fraud subtype.

| Subtype | Short description | Dataset |
|---|---|---|
| Generic Credit-Card Fraud | A transaction is labelled as fraudulent through `is_fraud`, without a more detailed ground-truth fraud category. | Sparkov |

---

## 14. E-Commerce / Online Transaction Fraud

**Fraud family:** E-Commerce / Online Transaction Fraud  
**Short description:** An online transaction is labelled fraudulent using payment, card, identity, device, email, and behavioural information, but no explicit subtype is provided.

| Subtype | Short description | Dataset |
|---|---|---|
| Generic E-Commerce Fraud | A transaction is labelled fraudulent through `isFraud`, without a public ground-truth subtype such as account takeover or stolen-card fraud. | IEEE-CIS |

---

## 15. Unclassified Money Laundering

**Fraud family:** Unclassified Money Laundering  
**Short description:** A transaction is explicitly labelled as laundering but is not assigned to one of the named laundering-pattern categories.

| Subtype | Short description | Dataset |
|---|---|---|
| Unclassified / Unknown Laundering | `Is Laundering = 1`, but the transaction is not mapped to a named graph typology in the associated pattern data. | IBM AML |

---

# Compact Reference

| Fraud Family | Subtype | Dataset |
|---|---|---|
| Structuring / Fragmentation | Structuring | SAML-D |
| Structuring / Fragmentation | Smurfing | SAML-D |
| Structuring / Fragmentation | High-Frequency Small Transactions | Amaretto |
| Fan-In / Consolidation | Fan-In | SAML-D, IBM AML |
| Fan-In / Consolidation | Layered Fan-In | SAML-D |
| Fan-Out / Distribution | Fan-Out | SAML-D, IBM AML |
| Fan-Out / Distribution | Layered Fan-Out | SAML-D |
| Scatter / Gather Networks | Scatter-Gather | SAML-D, IBM AML |
| Scatter / Gather Networks | Gather-Scatter | SAML-D, IBM AML |
| Circular Movement | Cycle | SAML-D, IBM AML |
| Bipartite / Layered Networks | Bipartite | SAML-D, IBM AML |
| Bipartite / Layered Networks | Stacked Bipartite | SAML-D |
| Bipartite / Layered Networks | Stack | IBM AML |
| Irregular Network Movement | Random | IBM AML |
| Rapid Pass-Through | Deposit-Send | SAML-D |
| Rapid Pass-Through | Rapid Large Collateral Movement | Amaretto |
| Cash / Large Outflow | Cash Withdrawal | SAML-D |
| Cash / Large Outflow | Single Large Transaction | SAML-D |
| Cash / Large Outflow | Large Asset Withdrawal | Amaretto |
| Behavioural Change | Behavioural Change 1 | SAML-D |
| Behavioural Change | Behavioural Change 2 | SAML-D |
| Behavioural Change | Unusual-Time Trading | Amaretto |
| Behavioural Change | Rounded Transaction Amounts | Amaretto |
| Trade-Based Money Laundering | Over-Invoicing | SAML-D |
| Account Takeover / Account Draining | Fraudulent Transfer | PaySim |
| Account Takeover / Account Draining | Fraudulent Cash-Out | PaySim |
| Credit-Card Transaction Fraud | Generic Credit-Card Fraud | Sparkov |
| E-Commerce / Online Transaction Fraud | Generic E-Commerce Fraud | IEEE-CIS |
| Unclassified Money Laundering | Unclassified / Unknown Laundering | IBM AML |

---

## Note on Ground Truth

Only treat a subtype as ground truth when the source dataset explicitly provides that subtype or an associated pattern annotation.

For example:

```text
IEEE-CIS:
DeviceType = mobile
isFraud = 1
```

does **not** prove that the fraud subtype is `DEVICE_FRAUD`.

Similarly:

```text
Sparkov:
large customer-to-merchant distance
is_fraud = 1
```

does **not** prove a `LOCATION_FRAUD` subtype.

Those values are predictive features, not subtype labels.

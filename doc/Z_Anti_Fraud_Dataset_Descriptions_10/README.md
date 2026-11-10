# Z-Anti-Fraud — Ten Independent Dataset Descriptions

This package contains **one detailed Markdown file for each of the ten datasets** discussed for the project. The goal is to inspect source-native models before agreeing on a common event structure.

## What every description contains

1. Dataset introduction, type, source, scale and provenance.
2. Dataset-native entity relationships and the unit of observation.
3. Field-by-field descriptions, including label and point-in-time cautions.
4. A small sample (with explicit provenance and whether rows are illustrative).
5. Dataset-specific label semantics and important interpretation traps.
6. Advantages, limitations and threats to experimental validity.
7. Suggested uses in Z-Anti-Fraud, sampling procedures and final assessment.

## File index

| # | Dataset | Markdown document | Example provenance |
|---|---|---|---|
| 1 | Fraud Detection Handbook | `descriptions/01_Fraud_Detection_Handbook.md` | Published Handbook example |
| 2 | RBA Login Dataset | `descriptions/02_RBA_Login_Dataset.md` | Illustrative (no official extract in upload) |
| 3 | Bank Account Fraud (BAF) | `descriptions/03_BAF_Bank_Account_Fraud.md` | Illustrative (no official extract in upload) |
| 4 | IBM TabFormer | `descriptions/04_IBM_TabFormer.md` | Illustrative (no official extract in upload) |
| 5 | SAML-D | `descriptions/05_SAML_D.md` | User-uploaded sample; see notes |
| 6 | Sparkov | `descriptions/06_Sparkov.md` | User-uploaded sample; see notes |
| 7 | IEEE-CIS Fraud Detection | `descriptions/07_IEEE_CIS.md` | User-uploaded sample; see notes |
| 8 | Amaretto | `descriptions/08_Amaretto.md` | User-uploaded sample; see notes |
| 9 | IBM AML / AMLworld | `descriptions/09_IBM_AML.md` | User-uploaded sample; see notes |
| 10 | PaySim | `descriptions/10_PaySim.md` | User-uploaded sample; see notes |

## Directory layout

- `descriptions/`: ten standalone reports.
- `samples/`: original uploaded sample CSVs, the published Handbook example CSV and a provenance register. Not training corpora.
- `reference_models/`: exact Markdown source models uploaded by the user (unchanged).

## Scope and limitations

The 10 Markdown documents are **descriptive analyses**, not empirical evaluations of the complete downloaded datasets. The source material includes actual tiny CSV rows for six datasets, a published Handbook output table, and schemas only (no actual CSV records) for RBA, BAF and TabFormer. Those three reports contain clearly marked illustrative examples. Do not claim that example statistics reflect official dataset distributions.

Ground-truth labels are **offline** for benchmarking/training. Features calculated after a decision should not be used as pre-decision inputs. No unified Z-Anti-Fraud schema is proposed yet.


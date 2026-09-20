---
schema_version: 1
open_count: 1
waived_count: 0
fixed_count: 0
total_count: 1
last_updated: 2026-09-20T02:19:52.942Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 02 | unrun-verify | catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java |  | Task 3 human-check (navegacao manual GET /api/products com token BUYER x SELLER_ADMIN) nao executado — depende da stack do plano 02-03 (gateway + docker-compose) ainda nao existir | open |  | 2026-09-20T02:19:52.942Z |  |

````json
[
  {
    "id": 1,
    "kind": "unrun-verify",
    "phase": "02",
    "file": "catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java",
    "line": null,
    "description": "Task 3 human-check (navegacao manual GET /api/products com token BUYER x SELLER_ADMIN) nao executado — depende da stack do plano 02-03 (gateway + docker-compose) ainda nao existir",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-20T02:19:52.942Z",
    "resolved_at": null,
    "milestone": null
  }
]
````

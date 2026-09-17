# Feature Research

**Domain:** B2B wholesale order management (single seller, multiple buyer companies) — portfolio project
**Researched:** 2026-09-16
**Confidence:** MEDIUM (cross-checked across multiple independent industry sources: vendor blogs, OMS/B2B-commerce platform docs, credit-management guides; no single authoritative spec exists for this domain the way there is for, say, a language API — MEDIUM is the ceiling for this kind of survey)

## Context Note

This is a **portfolio project**, not a commercial product. The audience is technical recruiters/interviewers evaluating Java/Spring Boot microservices competency. That reframes the usual "table stakes vs differentiator" lens:

- **Table stakes here = what makes the demo *read as a credible, realistic B2B system*** to an evaluator, not what a paying wholesale customer would revolt without.
- **Differentiators here = what makes the portfolio stand out** relative to typical CRUD-only portfolio projects, by demonstrating harder engineering (sagas, async messaging, event-driven consistency) rather than by demonstrating business-feature breadth.
- **Anti-features = complexity that inflates scope without inflating perceived engineering skill** — and, per PROJECT.md, several of these are already explicitly out of scope. This file respects and does not re-litigate those decisions; it only flags anything *not yet decided* that would be a trap.

## Feature Landscape

### Table Stakes (Expected in a Credible B2B Order System)

Features an evaluator/domain-aware reader expects to see, or the system reads as a toy e-commerce cart rather than a B2B order platform.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Role-based access (buyer vs seller-admin) | B2B systems are never single-role; seller and buyer have fundamentally different capabilities and data visibility | LOW | Already in scope (JWT + BUYER/SELLER_ADMIN) |
| Seller-managed product catalog with pricing | Every B2B OMS source surveyed lists a seller/vendor-controlled catalog as foundational — buyers don't self-list products in a single-seller model | LOW-MEDIUM | Already in scope |
| Real inventory tracking with reservation on order | Distinguishes wholesale/distribution systems from generic e-commerce; oversold inventory is the classic B2B failure mode | MEDIUM | Already in scope; this is also the Core Value driver (saga) |
| Credit-limit-based order approval | Confirmed as a standard B2B pattern (see Credit Limit Approval Workflow research below) — B2C carts don't have this, B2B ones almost universally do | MEDIUM | Already in scope; matches researched best practice of "approval matrix" concept, simplified to a single threshold per buyer company |
| Order status lifecycle with clear state transitions | Buyers/sellers in B2B always need to know exactly where an order stands; ambiguous or missing statuses read as unfinished | LOW-MEDIUM | Already in scope (CREATED → PENDING_APPROVAL → APPROVED/REJECTED → CONFIRMED → SHIPPED → DELIVERED / CANCELLED) |
| Order approval/rejection action by seller | Table stakes complement to conditional approval — a status that nothing can move out of is a dead end | LOW | Already in scope |
| Shipping/carrier reference + tracking code on order | Buyers expect *some* fulfillment visibility; even a simulated carrier assignment satisfies this | LOW | Already in scope, simulated per decision |
| Notification/audit trail of key order events | B2B buyers and sellers expect a record of what happened and when (order created, approved, shipped, delivered) — also doubles as an audit log, which auditors/evaluators associate with "real" enterprise systems | LOW-MEDIUM | Already in scope (DynamoDB via LocalStack, consumed via SQS) |
| Per-buyer-company account scoping | A buyer company user should only see their own orders/pricing, not other buyer companies' — this is implicit in "multiple buyer companies" but worth stating explicitly as a requirement, not just an auth role | LOW-MEDIUM | Not explicitly stated in PROJECT.md's Active list — recommend making explicit in requirements so it isn't silently dropped during implementation (buyers are companies, not just users; a buyer-company concept with users belonging to it should exist) |
| Idempotent/consistent order creation despite async saga steps | Table stakes for *any* credible saga-based system — an order that can end up "confirmed" with no reserved stock, or duplicated on retry, undermines the entire architectural point of the project | MEDIUM-HIGH | Not a new feature, but a *quality bar* on the already-scoped saga; call out explicitly so it's tested, not just implemented happy-path |
| Basic order history / list view (buyer sees their orders, seller sees all orders) | Without this, there is no way to observe the system's core value end-to-end; universally present in every B2B OMS surveyed | LOW | Implicit in "create order" flow but should be an explicit requirement (read-side, not just write-side) |

### Differentiators (Stand Out in a Portfolio Review, Without Scope Bloat)

These are features that go beyond baseline CRUD and specifically showcase distributed-systems/Java-ecosystem competency valued in a Pleno Java role — they align with the project's stated Core Value (event-driven saga architecture), not with business-feature breadth.

| Feature | Value Proposition | Complexity | Notes |
|---------|--------------------|------------|-------|
| Saga-based orchestration across order/inventory/notification services (compensating actions on failure) | This *is* the stated Core Value; doing it well (not just happy path, but compensation when stock reservation fails) is the single highest-leverage differentiator for a Java microservices portfolio | HIGH | Already in scope in spirit ("Pedido falha/é cancelado se a reserva de estoque falhar"); recommend explicitly modeling this as a compensating transaction, and documenting it as an ADR — evaluators specifically look for saga/compensation reasoning, not just "it calls another service" |
| Idempotency keys / exactly-once-effect handling on SQS consumers | SQS (and most queues) offer at-least-once delivery; handling duplicate delivery correctly is a well-known "do they actually understand distributed messaging" signal for reviewers | MEDIUM | Natural extension of already-planned SQS consumption in inventory/notification services; low incremental cost if planned from the start, expensive to retrofit |
| Optimistic concurrency / row-level locking on inventory reservation | Prevents two concurrent orders from over-reserving the same stock — a classic B2B inventory bug and a good interview talking point (e.g., `SELECT ... FOR UPDATE` or a version column) | LOW-MEDIUM | Cheap to add given inventory-service already exists; strong signal of production-mindedness for a Java/Postgres candidate |
| Structured API documentation (OpenAPI/Swagger per service) | Expected in modern Java/Spring portfolios; low cost, high signal-to-noise for reviewers skimming the repo | LOW | springdoc-openapi is close to zero-config in Spring Boot; strongly recommend even though not yet in Active scope |
| Distributed tracing / correlation IDs across services (e.g., via Sleuth/Micrometer Tracing + a log aggregator, or even just propagated correlation-id headers/logs) | Demonstrates awareness of observability in microservices — a common pain point interviewers probe for | MEDIUM | Can be scoped down to "correlation ID propagated in logs" if full tracing infra (Zipkin/Jaeger) is too much; still valuable as a lighter differentiator |
| API Gateway-enforced rate limiting / centralized auth validation | Shows gateway is doing real work (not just routing) — reinforces the "I understand gateway patterns" narrative already implied by including one | LOW-MEDIUM | Natural extension of existing API Gateway component |
| Contract tests between services (e.g., Spring Cloud Contract or Pact) | Already implied by "contrato/E2E entre microsserviços" in Constraints — worth calling out as a differentiator because most portfolio projects skip it entirely | MEDIUM | Already in scope per Constraints; reinforcing it here so it isn't deprioritized during planning |
| Dead-letter queue handling for failed notification/inventory events | Shows understanding that async systems need a failure escape hatch, not just a happy path | LOW-MEDIUM | Cheap with LocalStack SQS (DLQ is a queue attribute); good complement to the saga differentiator |

### Anti-Features (Commonly Present in Real B2B OMS, Deliberately Excluded Here)

These map directly to PROJECT.md's existing Out of Scope list, confirmed against how commercial B2B OMS platforms typically implement them — included here so the rationale is traceable to research, not just asserted.

| Feature | Why Commercially Present | Why Problematic for This Portfolio | Alternative (already decided) |
|---------|---------------------------|--------------------------------------|-------------------------------|
| Real payment gateway integration (invoicing, payment terms, dunning) | Every commercial B2B OMS ties orders to receivables/payment terms | Pulls focus into a finance domain (accounts receivable, payment reconciliation) orthogonal to the demonstrated skill (order/inventory/saga architecture); adds a whole new integration surface (Stripe/etc.) for no architectural payoff | Mocked/simplified payment, per PROJECT.md |
| Real carrier API integration (rate shopping, label generation, live tracking webhooks) | Commercial systems integrate with EasyPost/Shippo/carrier APIs for live rates and tracking | External dependency, cost, and complexity with no bearing on the saga/microservices story; live carrier APIs are themselves often just fetched external data. Simulated carrier assignment already demonstrates "the concept of an external integration point" | Simulated carrier + tracking code as order attribute, per PROJECT.md |
| Multi-vendor marketplace (multiple sellers competing/listing on one platform) | Real B2B marketplaces (Faire, Alibaba, etc.) support many sellers | Multiplies data-model and authorization complexity (seller-scoped catalogs, seller payouts, cross-seller order splitting) without adding new saga/architecture lessons beyond what a single-seller model already teaches | Single seller, multiple buyers, per PROJECT.md |
| Intermediate shipping/transit statuses (AWAITING_PICKUP, IN_TRANSIT, OUT_FOR_DELIVERY, exception handling) | Real carriers/OMS expose granular tracking milestones | Since the carrier is simulated anyway, granular fake states add UI/data surface without adding believability — nothing is actually moving | Keep SHIPPED → DELIVERED only, per PROJECT.md |
| Dedicated shipping/logistics microservice | Large real systems split shipping/logistics into its own bounded context (rate calc, label service, carrier abstraction) | With shipping simulated as order attributes, a dedicated service would be a service with no real responsibility — it would exist only to look like more microservices, which is padding, not depth | Shipping data as order-service attributes, per PROJECT.md |
| Continuous live AWS deployment | Real B2B platforms run in production cloud infra | Ongoing cloud cost for a portfolio project with no paying users; also distracts from the core "can this candidate design an event-driven order system" question with a secondary "can they run AWS ops" question | LocalStack for AWS services (SQS/DynamoDB/S3), real deploy optional/on-demand, per PROJECT.md |
| Sophisticated multi-tier credit approval matrix (multiple approvers by dollar amount, credit committees, periodic re-underwriting) | Real B2B credit teams (per research) use tiered approval matrices and continuous risk monitoring | A single threshold-based approval (over/under credit limit → PENDING_APPROVAL or not) already demonstrates the *pattern*; multi-level approval chains are a business-rules feature, not an architecture feature, and would mostly add branching logic without new distributed-systems lessons | Keep single credit-limit threshold per buyer company, single seller-admin approval step (already implied by scope) |
| Full ERP/CRM/accounting system integration | Commercial B2B OMS advertise integration as a major feature (fabric, Kibo, Oro, etc. all lead with this) | There is no external ERP to integrate with in a portfolio context; building a fake ERP just to integrate with it is pure scope inflation | None needed — the microservices *are* the "integrated system" being demonstrated |
| Custom/tiered/contract pricing per buyer company, volume discounts | Confirmed as genuine table stakes in real B2B commerce (per research: "customer-specific pricing... table stakes") | Adds a pricing-rules engine dimension (contracts, tiers, currency overrides) that's orthogonal to the order/inventory saga story; real value is in demonstrating the order flow, not a pricing DSL | Single seller-set catalog price per product, applied uniformly; if there is future appetite, a *simple* per-buyer discount percentage would be the cheapest realistic step up — but not required |
| Quote-to-order workflow (RFQ, negotiated quotes before order creation) | Common in enterprise B2B where large orders are negotiated before becoming firm orders | Adds an entire pre-order object model and negotiation state machine; the order approval-by-credit-limit flow already covers the "orders aren't just always auto-accepted" narrative | Buyer creates order directly from catalog; approval gate is credit-based, not quote-based |
| Multi-warehouse / multi-node fulfillment with inventory allocation logic | Real distributors ship from multiple warehouses and need allocation/split-shipment logic | Significant complexity (allocation algorithms, partial fulfillment, split shipments) for a portfolio project whose story is about the *order lifecycle*, not logistics optimization | Single inventory pool per product in inventory-service |

## Feature Dependencies

```
Role-based access (BUYER/SELLER_ADMIN JWT)
    └──requires──> nothing (foundational)

Seller-managed catalog
    └──requires──> Role-based access (only SELLER_ADMIN can write)

Buyer creates order from catalog
    └──requires──> Seller-managed catalog (need products/prices to order)
    └──requires──> Per-buyer-company account scoping (order must belong to a buyer company)

Credit-limit-based approval (PENDING_APPROVAL)
    └──requires──> Buyer creates order (need an order to gate)
    └──requires──> Buyer company has a defined credit limit (data model prerequisite)

Seller approves/rejects pending orders
    └──requires──> Credit-limit-based approval (only exists because approval gate exists)

Inventory reservation via saga (order-service → inventory-service)
    └──requires──> Buyer creates order
    └──requires──> Real inventory tracking (inventory-service must hold real stock levels)
    └──enhances──> Saga-based orchestration differentiator (this IS the saga)

Order cancellation on reservation failure
    └──requires──> Inventory reservation via saga (failure path of the same mechanism)

Carrier assignment + tracking code
    └──requires──> Order reaches CONFIRMED (assignment happens post-confirmation, not before)

Notification history (DynamoDB via SQS)
    └──requires──> Every prior event-producing step (order created/approved/shipped/delivered all need to emit events)

Idempotency keys on SQS consumers ──enhances──> Saga-based orchestration (prevents duplicate-delivery bugs from corrupting the saga)

Optimistic concurrency on inventory ──enhances──> Inventory reservation via saga (prevents race-condition overselling)

Dead-letter queue handling ──enhances──> Notification history + Inventory reservation (failure visibility for both consumers)

Multi-tier credit approval matrix ──conflicts──> Portfolio scope goal (adds business-rule complexity with no new architecture lesson)
Multi-vendor marketplace ──conflicts──> Single-seller data model (would require reworking catalog/order ownership)
Dedicated shipping-service ──conflicts──> Decision to keep shipping as order-service attributes (would need to un-decide that first)
```

### Dependency Notes

- **Buyer creates order requires per-buyer-company account scoping:** Orders in a B2B system belong to a *company*, not just a user — this must be modeled from the first order-related phase, not retrofitted, or credit-limit checks (which are per-company) will have nothing to attach to.
- **Credit-limit approval requires a defined credit limit as company data:** This implies buyer-company must be a first-class entity (likely in auth-service or its own concept) with at least a credit-limit attribute before the order-service approval logic can be built — sequence this earlier in the roadmap.
- **Inventory reservation via saga requires real inventory tracking:** Cannot build the saga step before inventory-service has real, queryable stock levels; these two are effectively one phase's worth of dependency, not separable.
- **Idempotency keys and optimistic concurrency enhance (don't gate) the saga:** These can be added incrementally after the happy-path saga works, but are cheap enough to design in from the start — retrofitting idempotency into an already-built consumer is more expensive than including it in the first pass.
- **Carrier assignment depends on CONFIRMED status:** Sequencing matters — assigning a carrier before an order is confirmed (e.g., during PENDING_APPROVAL) would be semantically wrong and should be guarded against in implementation.
- **Anti-features conflict with stated architecture decisions:** Multi-tier credit approval, multi-vendor marketplace, and a dedicated shipping-service would each require reopening an already-made Key Decision in PROJECT.md — flagged as conflicts so future scope-creep proposals are recognized as such immediately.

## MVP Definition

Given this is a portfolio project already mid-scoped, "MVP" here means "smallest slice that proves the Core Value end-to-end" — everything in PROJECT.md's Active list is effectively the v1 scope. This section maps that scope onto MVP tiers plus the (currently missing) explicit reads/company-scoping items.

### Launch With (v1) — matches PROJECT.md Active scope, plus explicit gaps called out

- [ ] Role-based auth (BUYER/SELLER_ADMIN via JWT) — foundation for everything else
- [ ] Seller-managed catalog (products, prices) — needed before any order can be created
- [ ] Buyer-company entity with credit limit attribute — **not explicit in PROJECT.md; recommend adding to Active requirements**, since credit-limit approval has no data to check against otherwise
- [ ] Seller-managed inventory levels — needed before reservation logic can exist
- [ ] Buyer creates order from catalog — the core transaction
- [ ] Credit-limit-triggered conditional approval (PENDING_APPROVAL vs. straight-through) — the defining B2B business rule
- [ ] Seller approves/rejects pending orders — closes the approval loop
- [ ] Saga: order-service reserves stock in inventory-service via async event, with compensation/cancellation on failure — the stated Core Value
- [ ] Full order status lifecycle (CREATED → PENDING_APPROVAL → APPROVED/REJECTED → CONFIRMED → SHIPPED → DELIVERED/CANCELLED)
- [ ] Simulated carrier assignment + tracking code
- [ ] Notification history in DynamoDB, consumed via SQS
- [ ] Order list/detail views (buyer sees own orders; seller sees all) — **not explicit in PROJECT.md; recommend adding**, since a write-only system can't be demonstrated or evaluated
- [ ] docker-compose full local stack; CI/CD build+test pipeline; ADRs in Portuguese

### Add After Core Works (v1.x) — cheap, high-signal differentiators to layer on once the saga is solid

- [ ] Idempotency handling on SQS consumers — add once basic consumers work, before declaring the saga "done"
- [ ] Optimistic concurrency/locking on inventory reservation — add once naive reservation works, to close the obvious race condition
- [ ] OpenAPI/Swagger docs per service — near-zero cost with springdoc-openapi
- [ ] Correlation-ID propagation across service logs — cheap observability win
- [ ] Dead-letter queue on SQS consumers — cheap resilience win once base queues exist
- [ ] Contract tests between services (already in Constraints — ensure it isn't dropped under time pressure)

### Future Consideration (v2+, only if there's appetite beyond the target-job portfolio scope)

- [ ] Distributed tracing (Zipkin/Jaeger) — defer unless observability becomes a specific interview talking point to prepare for
- [ ] Simple per-buyer-company discount percentage on pricing — defer; only worth it if a reviewer specifically probes "how would you handle different prices per customer"
- [ ] Rate limiting at API Gateway — defer; nice but not core to the saga/order story
- [ ] Real (opt-in, on-demand) AWS deployment — defer per existing decision; only spin up for a live demo if requested

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Role-based auth (JWT) | HIGH | LOW | P1 |
| Seller catalog management | HIGH | LOW | P1 |
| Buyer-company entity + credit limit | HIGH | LOW | P1 |
| Inventory tracking | HIGH | MEDIUM | P1 |
| Order creation | HIGH | LOW | P1 |
| Credit-limit conditional approval | HIGH | MEDIUM | P1 |
| Seller approve/reject action | HIGH | LOW | P1 |
| Saga: stock reservation + compensation | HIGH | HIGH | P1 |
| Order status lifecycle | HIGH | LOW-MEDIUM | P1 |
| Simulated carrier + tracking | MEDIUM | LOW | P1 |
| Notification history (DynamoDB/SQS) | MEDIUM | MEDIUM | P1 |
| Order list/detail views | HIGH | LOW | P1 |
| Idempotency on SQS consumers | MEDIUM | MEDIUM | P2 |
| Optimistic concurrency on inventory | MEDIUM | LOW-MEDIUM | P2 |
| OpenAPI/Swagger docs | MEDIUM | LOW | P2 |
| Correlation-ID log propagation | MEDIUM | LOW | P2 |
| Dead-letter queues | LOW-MEDIUM | LOW | P2 |
| Contract tests between services | MEDIUM | MEDIUM | P2 |
| Distributed tracing (Zipkin/Jaeger) | LOW | MEDIUM-HIGH | P3 |
| Per-buyer discount pricing | LOW | LOW-MEDIUM | P3 |
| API Gateway rate limiting | LOW | LOW | P3 |
| Real AWS deployment | LOW | HIGH | P3 |

**Priority key:**
- P1: Must have — this is the already-decided v1 scope plus the two gaps identified (buyer-company/credit-limit as data, order list views)
- P2: Should have — cheap engineering differentiators to add once P1 works, strongly recommended for portfolio impact per effort ratio
- P3: Nice to have — explicitly deferred; only pursue if extra time/interest remains

## Competitor Feature Analysis

"Competitors" here means representative commercial B2B OMS/wholesale platforms surveyed (Orderwerks, OrderEase, Mintsoft, fabric, Kibo Commerce, Oro Inc., Adobe Commerce B2B) — used to validate what's realistic, not to clone feature-for-feature.

| Feature | Typical Commercial Approach | Our Approach |
|---------|------------------------------|--------------|
| Pricing | Per-account contract pricing, tiered/volume discounts, multi-currency | Single seller-set catalog price, uniform across buyers (simpler, still realistic for a single-seller model) |
| Order capture channels | Web storefront + rep-assisted + EDI/email-to-order normalization | Single API-driven order creation path (buyer app/API only) — sufficient to demonstrate the flow without multi-channel ingestion complexity |
| Credit/approval | Tiered approval matrix, credit analyst review, continuous monitoring | Single threshold-based approval gate per buyer company, one seller-admin approval action — captures the *pattern* at minimal complexity |
| Fulfillment | Multi-warehouse allocation, live carrier rate/label APIs, granular transit statuses | Single inventory pool, simulated carrier, two-state shipping (SHIPPED/DELIVERED) — deliberately flattened per existing decisions |
| Integration | ERP/CRM/accounting sync | None (internal microservices are the "integration" being demonstrated) |
| Audit/notifications | Event log + email/SMS/webhook notifications | Notification history persisted to DynamoDB via SQS-consumed events — captures the audit-trail concept without needing real email/SMS delivery |

## Sources

- [B2B Order Management System for Wholesalers Built for Growing Businesses (LinkedIn)](https://www.linkedin.com/pulse/b2b-order-management-system-wholesalers-built-growing-businesses-5uwsf)
- [Orderwerks — B2B Order Management Software for Wholesale Distributors](https://www.orderwerks.com/features/order-management)
- [OrderEase — B2B Wholesale Order Management System Features](https://www.orderease.com/blog/b2b-wholesale-order-management-system-features)
- [OrderEase — Top 7 Features to Look For in a B2B Online Ordering System](https://www.orderease.com/community/top-7-features-to-look-for-in-a-b2b-online-ordering-system)
- [Mintsoft — Wholesale Order Management: A Complete Guide](https://www.mintsoft.com/order-management/guide-to-wholesale-order-management/)
- [Moxo — 5 Best B2B Order Management Software](https://www.moxo.com/blog/best-b2b-order-management-software)
- [Akaunting — Best Practices in B2B Credit Risk Management](https://akaunting.com/blog/b2b-credit-risk-management)
- [Resolve — A Step-by-Step Guide on Choosing the Right B2B Credit Management System](https://resolvepay.com/blog/post/a-step-by-step-guide-on-choosing-the-right-b2b-credit-management-system/)
- [Adobe Commerce — Manage Company Credit (B2B docs)](https://experienceleague.adobe.com/en/docs/commerce-admin/b2b/companies/credit-company)
- [Moxo — Credit Limit Approval Process](https://www.moxo.com/process/credit-limit-approval)
- [CreditPulse — B2B Credit Management Best Practices](https://www.creditpulse.com/blog/b2b-credit-management-best-practices-guide)
- [CreditPulse — Credit Limit Management: How B2B Teams Set, Track](https://www.creditpulse.com/blog/credit-limit-management-b2b-guide)
- [Bectran — The CFO's Guide to Credit Order Hold Processing](https://www.bectran.com/post/the-cfos-guide-to-credit-order-hold-processing)
- [fabric Inc. — What Are Core Features of B2B E-Commerce Platforms?](https://fabric.inc/blog/commerce/features-of-b2b-ecommerce)
- [Kibo Commerce — Best Order Management Systems and Software 2026](https://kibocommerce.com/blog/best-order-management-systems/)
- [Oro Inc. — Enterprise B2B eCommerce: The Complete Guide](https://oroinc.com/b2b-ecommerce/blog/enterprise-b2b-ecommerce/)
- [MarTech — The New Must-Haves in B2B Ecommerce Tech Stacks](https://martech.org/the-new-must-haves-in-b2b-ecommerce-tech-stacks-go-beyond-crm-and-cms/)
- Project context: `.planning/PROJECT.md` (OrderFlow scope, requirements, and out-of-scope decisions)

---
*Feature research for: B2B wholesale order management (portfolio project)*
*Researched: 2026-09-16*

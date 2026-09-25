# ADR-005: LLM provider, fallback and data residency

| | |
|---|---|
| Status | **Recommended — pending legal review** (owner decision Q-14, 2026-09-25). Not final until legal/DPO sign-off on cross-border processing |
| Date | 2026-09-25 |
| Deciders | Repo owner; legal / DPO review required before production |
| Related | [llm-architecture.md](../llm-architecture.md), [security.md](../security.md) §6 and §9, risks R-04, R-05, R-06, R-08, R-14, R-19, R-21 |

## Context

SPEC §2.6 fixes Anthropic Claude via Spring AI: `claude-sonnet-5` for live chat and
the current Haiku-tier model (config) for proactive diagnosis (researched below as Claude Haiku 4.5, the current one). SPEC §2.5 asks where the LLM
processes customer data, and whether Claude can be reached through a cloud region that
keeps data in India. **Verify; do not assume.** Decision Q-10 limits that research to
**official Amazon Bedrock and Google Vertex AI documentation**, cited with retrieval date.
If no India region exists, ADR-005 recommends the global endpoint with strict data
minimisation, subject to legal assessment, with template-only mode as the fall-back. The
owner decides.

What the LLM receives (security.md §6): masked MSISDN (last 4 digits), bill periods,
charge categories and amounts, usage quantities, plan/add-on catalogue data, and the
customer's own chat text. No name, address, full MSISDN or payment data. The customer's
free text can still contain personal data they type themselves.

## Research: is Claude available in an India region? (retrieved 2026-09-25)

### Amazon Bedrock

Sources (retrieved 2026-09-25):
- Model card, Claude Sonnet 5:
  <https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-5.html>
- Model card, Claude Haiku 4.5:
  <https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-4-5.html>
- Regional availability overview:
  <https://docs.aws.amazon.com/bedrock/latest/userguide/models-region-compatibility.html>

| Model | Region | In-Region | Geo cross-Region | Global cross-Region |
|---|---|---|---|---|
| Claude Sonnet 5 (`bedrock-runtime`) | ap-south-1 (Mumbai) | No | No | **Yes** |
| Claude Sonnet 5 (`bedrock-runtime`) | ap-south-2 (Hyderabad) | No | No | **Yes** |
| Claude Haiku 4.5 (`bedrock-runtime`) | ap-south-1 (Mumbai) | No | No | **Yes** |
| Claude Haiku 4.5 (`bedrock-runtime`) | ap-south-2 (Hyderabad) | No | No | **Yes** |
| Both (`bedrock-mantle`, the single-Region endpoint) | Any India region | Not listed | — | — |

Relevant statements on the model cards (paraphrased):
- For these models, `bedrock-runtime` needs a **geo or global inference profile**. The bare
  model id is not supported for on-demand use. Geo and global profiles can route requests
  outside the source Region and do **not** give single-Region data residency.
- Single-Region inference is available only on the `bedrock-mantle` endpoint. Its region
  list (Sonnet 5: us-east-1, us-gov-west-1, eu-north-1, eu-west-1, ap-southeast-4; Haiku
  4.5 adds us-east-2, us-west-2, ap-northeast-1) **contains no India region**.
- The published geo profiles are **US, EU and AU** for Sonnet 5, and **US, EU, AU and JP**
  for Haiku 4.5. There is **no India or APAC geo profile** for either model.
- "Global" is described as routing worldwide with no residency constraints. Its
  destination list includes ap-south-1 and ap-south-2 among many other regions, so
  processing *may* happen in India but is not guaranteed.
- Lifecycle: the Bedrock Haiku 4.5 card shows "Model EOL date: No sooner than 10/1/2026".
  Sonnet 5 shows "EOL no sooner than June 30, 2027".

**Conclusion (Bedrock):** Claude **cannot be pinned to India** on Bedrock today. The only
option from an India region is the global profile, which gives no residency guarantee.

### Google Vertex AI

Sources (retrieved 2026-09-25; both pages "Last updated 2026-09-24 UTC"). The older URL
`cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude` redirects to:
- Claude Sonnet 5:
  <https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/sonnet-5>
- Claude Haiku 4.5:
  <https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/haiku-4-5>

| Model | Model availability | ML processing locations listed | India region? |
|---|---|---|---|
| Claude Sonnet 5 | United States multi-region, Europe multi-region, global endpoint | United States multi-region, Europe multi-region, Asia Pacific `asia-southeast1` | **No** (`asia-south1` / `asia-south2` not listed) |
| Claude Haiku 4.5 | `us-east5`, `europe-west1`, global endpoint | United States multi-region, Europe multi-region | **No** |

Lifecycle on Vertex: Sonnet 5 retirement "not sooner than December 24, 2026"; Haiku 4.5
retirement "not sooner than October 15, 2026".

**Conclusion (Vertex AI):** **no India region** for either model.

### Anthropic first-party API (context from Phase 1, not part of the Q-10 research scope)

The pricing page (retrieved 2026-09-25, A-20) lists only the `global` (default) and `us`
inference geographies (`us` costs 1.1x). There is no India option.

### Research conclusion

**No official India-region option exists for either model on Bedrock or Vertex AI as of
2026-09-25.** Under Q-10, the owner decides the ADR-005 option. This ADR recommends one.

## Options

| Option | Residency | Cost vs baseline | Engineering | Other |
|---|---|---|---|---|
| **A. Anthropic API, global routing, strict data minimisation** (recommended) | Processing location not guaranteed; no India option | Baseline (A-20) | Baseline: Spring AI `spring-ai-anthropic`; prompt caching verified in Spring AI 1.1.8 sources and re-verified in the pinned 2.0.1 (ADR-008) | Anthropic lifecycle (retirement dates on the Anthropic deprecations page) |
| A′. Anthropic API, `inference_geo: us` | Pinned to the US (not India) | +10% on all tokens (~+$7k/month at 1x) | Same as A | Makes the location *known* and contractible, but outside India |
| B. Bedrock from ap-south-1, `global.*` profile | Not guaranteed (global routing) | Bedrock pricing (separate) | Different Spring AI module (Bedrock Converse); prompt caching and adaptive-thinking support would need fresh verification (Q-2 again); PrivateLink from the VPC | AWS-native IAM, billing and quotas; Haiku 4.5 EOL "no sooner than 10/1/2026" on Bedrock |
| C. Vertex AI (global or US/EU) | Not India | Vertex pricing | Cross-cloud from AWS (egress, a second IAM plane) | No benefit over A for India |
| D. Self-hosted open-weight model in India (EKS GPU nodes) | In-country | GPU cost; quality and tool-calling risk | Large: serving, evals, capacity | **Rejected** (see below). The local Ollama profile (SPEC §2.6) is for development only |
| E. Template-only mode (no LLM) | No LLM processing | LLM cost 0 | Already required as the fallback (SPEC §2.6) | Loses conversational answers; diagnosis numbers stay identical |

## Rejected option: self-hosted open-weight model in an Indian region

Running an open-weight model on GPU nodes in ap-south-1 would keep all processing in India.
It is **rejected for v1** for these reasons:

| Reason | Detail |
|---|---|
| **Tool-calling reliability** | The agent depends on correct multi-step tool use (3–8 tool calls per turn, parallel calls, strict JSON arguments, the `recordDiagnosis` reporting tool). Wrong or malformed tool calls produce wrong investigations or loops (R-10). The design, its guardrails and its latency budget were sized on frontier hosted models |
| **Eval quality** | NFR-07 (≥ 95% correct primary cause) and NFR-08 (zero invented issues on normal bills) are quality gates. An open-weight model would have to pass the same eval suite (llm-architecture.md §15), and there is no evidence yet that one does. The grounding gates would also fall back to templates more often, which lowers the "full capability" SLI |
| **Operational burden** | GPU capacity planning for a 7.5 → 75 turns/s peak, model serving (batching, KV cache, streaming), upgrades, security patching of the serving stack, and on-call for all of it. The team is small (A-45/A-46), and a hosted API needs none of this |
| **Cost** | GPU nodes must be provisioned for the chat peak (bursty, 2 days per cycle) and sit mostly idle for the rest of the month. With the long contexts of tool loops (≈ 12k input tokens per call, A-15), HA needs several large GPU instances per AZ. That fixed cost is not offset at 1x, and the prompt-caching savings (C-1) would have to be rebuilt in the serving stack |

It remains a **candidate for re-evaluation** if legal review rejects cross-border processing
and no in-country hosted option has appeared. It would then be compared with template-only
mode (E) on the eval suite and on total cost.

## Recommendation (accepted as "recommended — pending legal review", Q-14)

1. **Option A: Anthropic API, global routing,** behind the Spring AI `ChatModel`
   abstraction. It has the lowest cost and the least engineering, and Spring AI support
   for prompt caching is verified in source.
2. **Strict data minimisation** as the main control (security.md §6): masked MSISDN, no
   name or address, charges and usage only. A pre-send PII scrubber on customer free text
   (long digit sequences, e-mail addresses and card-like numbers are masked before the
   request). The account id is sent to the LLM only as an opaque per-conversation alias.
3. **Subject to legal assessment** (DPDP Act 2023 cross-border transfer rules, sectoral
   rules, the processor agreement with Anthropic). Marked **FOR LEGAL REVIEW**; this ADR
   does not claim compliance.
4. **Template-only mode (E) as the fall-back and kill switch.** One runtime flag
   (`billshock.llm.mode = TEMPLATE_ONLY`) routes every turn to the deterministic templates.
   It is used if legal review rejects option A, during a provider outage, or as a
   per-segment restriction.
5. **Revisit quarterly**, and immediately if Bedrock or Vertex AI announces an India
   region or India geo profile for a Claude model we use. Switching to Bedrock is a
   configuration change plus a different Spring AI model module, and it requires
   re-verifying prompt caching (Q-2) in that module before switching.

## Fallback and resilience (independent of the residency choice)

- Resilience4j per ChatClient: timeout (chat 20 s per LLM call; proactive 30 s), retry with
  exponential backoff and jitter on 429/5xx/overloaded (max 2 retries for chat, 4 for
  proactive), circuit breaker (50% failure over a 20-call window → open for 30 s).
- **Fallback:** on `LlmUnavailableException` (timeout, open breaker, retries exhausted) the
  turn is answered through the deterministic templates (Template Method), using the
  already computed `diffBills` result. The fallback is counted in `llm_fallback_total` and
  in the "available" SLI (NFR-05, Q-4).
- **No automatic cross-provider failover in v1.** A second provider would send the same data
  to another processor, which changes the residency and legal position. It can be added
  later behind the same abstraction if the owner and legal approve it.

## Decision

**2026-09-25 (owner, Q-14): option A + strict data minimisation + template-only mode (E) as
the fall-back and kill switch is the recommended option, pending legal review.** Option D
(self-hosted open-weight model in India) is rejected for v1 (reasons above). The decision
becomes final when legal/DPO sign-off on cross-border processing is recorded here (date and
reviewer). Until then, only synthetic data is processed.

## Consequences (if the recommendation is accepted)

- R-05 stays **open, mitigated**. Residency is not guaranteed; exposure is limited to
  masked, charge-level data.
- A production launch needs legal sign-off on cross-border processing. Until then,
  staging and demos use synthetic data only (SPEC §7).
- **Model lifecycle risk (new, R-21):** Haiku 4.5's earliest retirement dates are close.
  Anthropic API: "not sooner than October 15, 2026" (model deprecations page, retrieved
  2026-09-25). Vertex: October 15, 2026. Bedrock: October 1, 2026. Anthropic gives at least
  60 days' notice before retirement. Model ids are config (SPEC §2.6). Per Q-15, no
  fall-back model is chosen now; any switch follows the eval-gated process in
  llm-architecture.md §15.

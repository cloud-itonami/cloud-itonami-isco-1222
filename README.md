# cloud-itonami-isco-1222

Open Business Blueprint for **ISCO-08 1222**: Advertising and Public Relations Managers — an ISCO
**Wave 1 (design & governance)** occupation per ADR-2607121000. This
is the FIRST wave-1 blueprint batch: management work is cognitive
(no robotics gate), sequenced after the wave-0 cognitive substrate in
rollout priority.

**Maturity: `:implemented`** — AdvertisingPRManagementAdvisor ⊣
AdvertisingPRManagementGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
13 tests / 27 assertions green.

The advertising HARD invariants — both registered sets, checked
deterministically:

1. **Claim substantiation** — every claim in the copy must be a member
   of this client's registered substantiation set (subset check).
   Advertising is the citation of registered evidence, not creative
   writing; another client's evidence does not count.
2. **Prohibited claims** — the intersection of the copy's claims and
   the registered prohibition table must be empty. The blacklist is
   arithmetic, not tone — and regulation outranks evidence (a
   substantiated claim that is also prohibited still holds).

Also HARD: unregistered organization, non-`:propose` effect.
Escalations (always human sign-off): `:publish-ad` /
`:issue-press-release` (external publication), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.

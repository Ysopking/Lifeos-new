# B475 — Context Retrieval v2

B475 upgrades language context retrieval from broad term/tag relevance to an explicit second-pass
need contract.

A first language pass can now produce LanguageContextRetrievalNeeds containing:

- exact Photon revisions that must be reconsidered
- runtime-supplied state-dimension keys from B472
- TemporalEpisodeGraph ids
- semantic types
- preferred object kinds
- realization tags such as projected/current/history

LanguageContextRetriever keeps its bounded index-only behavior. It adds targeted PhotonIndexQuery
lanes for those needs and reserves exact requested revisions before broad ranking, so an old but exact
reference cannot be pushed out by newer unrelated high-mass photons.

PhotonLanguageContextBuilder also preserves state-dimension, episode and realization tags on
LanguageContextItem, allowing reference and discourse logic to reason structurally rather than only
through token overlap.

Hard invariants:

- no full vault scan
- exact requested revision is not replaced by a newer/different revision
- targeted retrieval remains bounded
- retrieval need != truth
- retrieved context != execution authority
- runtime can supply state/episode needs without core/language depending on core/runtime

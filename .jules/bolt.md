# Bolt Journal - Performance Learnings

## 2025-05-18 - Zero-Allocation Subdomain Lookup in Domain Blocklists
**Learning:** String `substring()` allocations during domain checking on hot paths (e.g., WebView resource request interception) cause repeated garbage collection pressure. Passing offset indices directly into hash functions for `CharSequence` avoids all heap allocations during lookup.
**Action:** Always prefer index-based slicing (`hash(s, startIndex)`) over `substring()` or `split()` when hashing string prefixes/suffixes in hot paths.

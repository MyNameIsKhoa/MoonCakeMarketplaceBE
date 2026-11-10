# Order query response additions

`GET /api/orders` retains its existing Spring Page contract, sorting, page-size limit,
customer scope, and existing summary fields. Each summary additionally includes:

| Field | Java type | Meaning |
| --- | --- | --- |
| `sellerOrderCount` | `int` | Number of child seller orders. |
| `itemCount` | `int` | Number of order item rows across those children; not summed quantities. |
| `sellerOrderStatuses` | `List<String>` | Distinct current child statuses, in seller-order ID order. |
| `hasCancellation` | `boolean` | Any child has a cancellation record, including REQUESTED, APPROVED, or REJECTED history. |

Orders with no children return `0`, `0`, `[]`, and `false`. Associations are loaded
in batches for the current customer page, using only Commerce's local database.
No Catalog or Identity calls are made for this list.

`GET /api/orders/{orderId}` still checks customer ownership before reading children
or performing optional image enrichment. Item responses add `imageUrl` (`String`,
nullable), intended for display. The FE should prefer `imageUrl` and may fall back to
`imageUrlSnapshot`. `imageUrlSnapshot` always contains the original stored value.

For a null or blank snapshot, the customer detail endpoint may supply the current
Catalog image only when the returned variant ID and store ID match the order item
and its seller order. Missing variant IDs, mismatches, and unavailable Catalog data
leave the image missing. Distinct missing variant IDs are requested in batches of
at most 100; Catalog failures do not fail the order detail response. Existing images,
stored snapshots, item names, quantities, prices, and all financial fields stay intact.
The seller-order detail endpoint exposes the additive display field using its stored
snapshot only, without an additional Catalog lookup.

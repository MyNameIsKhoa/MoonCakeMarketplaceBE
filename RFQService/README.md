# RFQ access and quotation image contracts

## B2B access

- `GET /api/rfqs` (including `mine=false`) requires a bearer token and a supplier profile. Unverified suppliers retain read access but cannot submit or revise quotations. The feed contains public RFQs and RFQs inviting that supplier; expired open RFQs, drafts and cancelled RFQs stay excluded.
- `GET /api/rfqs?mine=true` requires a customer profile **without a supplier profile**. Identity resolves the customer ID from the authenticated user; a query parameter cannot select another customer. A dual-profile account receives `403 CUSTOMER_MODE_REQUIRED` for buyer actions, including creating/editing/closing/cancelling RFQs and accepting/rejecting quotations.
- `GET /api/rfqs/{rfqId}` requires authentication. Customers can read only their own RFQs, including their private fields and unpublished states. Suppliers can read published public RFQs or RFQs inviting them, with buyer ID, delivery address and private customization fields hidden. An invited supplier sees only its own target stores. Other customer/uninvited requests return `404 RFQ_NOT_FOUND`.
- Supplier mode also takes precedence when listing/reading quotations and order links: a dual-profile account cannot use its customer profile to see other suppliers' quotations.
- Anonymous list/detail requests return 401; feed requests without a supplier profile return `403 SUPPLIER_REQUIRED`. Identity failures fail closed rather than degrading to public access. Existing verification and ownership checks remain in place for quotation writes.
- Conversation membership remains independent of the selected B2B tab, so existing chat threads remain accessible to their participants.

## Image snapshots

Migration **`V11__quotation_item_image_snapshot.sql`** adds nullable `dbo.quotation_item.image_url_snapshot` (`NVARCHAR(500)`). It leaves existing quotation rows and conversion JSON unchanged.

Quotation create/revise responses and detail responses add nullable `items[].imageUrlSnapshot`. For catalog items this is the Catalog product/variant snapshot's `imageUrl`, saved with that quotation version after existing product/store/supplier/status validation. The caller cannot supply an image URL. A revised version captures the current image independently; replaying create/revise returns the original saved version. Missing catalog images remain null.

Custom items save the fixed URL `/images/placeholders/custom-order.svg`. Historical custom rows with null images expose that same placeholder without writing back to the row. Historical variant rows with no image remain null; clients should use their display fallback. RFQ performs no live image lookup when accepting a quotation or retrying a conversion.

Acceptance sends the saved image as Commerce's existing `items[].imageUrlSnapshot`. The complete accepted payload is persisted in `quotation_conversion.snapshot_json` before Commerce is called. Client retries and background recovery reuse it unchanged, including null images in pre-migration conversions. Display fallback must never rewrite conversion JSON or affect Commerce's request fingerprint.

## Caller audit and handoff

The web caller `src/features/rfq/api.ts` uses the shared authenticated `apiRequest` helper. The mobile caller `MoonCakeMarketplaceMobile/lib/features/b2b_rfq/data/rfq_api.dart` uses `DioClient`, which also attaches the bearer token. The mobile screen `b2b_screens.dart` selects `mine` from its subtab and still exposes both buyer and supplier tabs. Frontend owners must gate/reset these tabs by B2B mode, handle 401/403, and provide the agreed placeholder asset and broken-image fallback. No frontend or Catalog upload files are owned by this change.

## Regression coverage

- `RfqAccessIntegrationTest`: real HTTP security chain and Identity profile resolution; anonymous/customer/admin/supplier/pending/dual-profile access, invitation filtering, private fields, quotation privacy and downstream failures.
- `QuotationManagementServiceTest` / `QuotationManagementIntegrationTest`: Catalog-authoritative images, custom placeholder, URL storage limit, immutable version images and create/revise replay with Catalog unavailable.
- `QuotationAcceptanceIntegrationTest`: image propagation, identical payload on client/background retries, legacy custom/variant rows and pre-migration conversion JSON.

Run the RFQ Maven tests with JDK 21. On the sandbox workstation use the existing Mockito agent and temporary directory through `JDK_JAVA_OPTIONS` as described by the parent task.

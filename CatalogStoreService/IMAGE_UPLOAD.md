# Catalog local image uploads

`POST /api/products/{productId}/images` through the API Gateway accepts
`multipart/form-data` with one `file` part and a supplier Bearer token. Catalog
rechecks the verified supplier, store ownership and store suspension before
writing. Create the draft product first; then upload from its variant editor.

Success is `201 Created` with a `Location` header and JSON:

```json
{
  "url": "http://localhost:8080/uploads/catalog/<server-uuid>.png",
  "contentType": "image/png",
  "width": 800,
  "height": 600,
  "size": 123456
}
```

Only JPEG/PNG are accepted: nonempty, at most 5 MiB, dimensions 1–4096 pixels
per side and at most 16,000,000 pixels total. The declared MIME type must match
the detected format. Catalog decodes and re-encodes pixels, discarding metadata
and trailing content, and ignores the client filename. SVG and other formats
are rejected. Validation returns 400; multipart size overflow returns 413;
unauthenticated/unauthorized requests return 401/403, and missing products return
404. Existing supplier verification errors retain Catalog's 400 response.

`GET /uploads/catalog/{server-uuid}.{png|jpg}` serves persisted images with the
correct MIME type, `nosniff` and public immutable caching. Only generated names
are served; traversal, symlinks and missing files cannot be read. The upload does
not change a variant: use the returned `url` in the existing variant create/update
request's `imageUrl` field. Cancelling the editor leaves the uploaded file on disk.

Configuration in Catalog:

- `CATALOG_UPLOAD_DIR`: external directory; defaults to
  `${user.home}/.mooncake/uploads/catalog`. Paths inside `src` or `target` are
  rejected. The service account needs write access. Keep this directory across
  restarts and mount a persistent volume here in containers.
- `CATALOG_PUBLIC_BASE_URL`: browser-accessible Gateway base URL, default
  `http://localhost:8080`. Use the deployed HTTPS Gateway URL in production.
- `CORS_ALLOWED_ORIGINS`: comma-separated browser origins, same setting as Gateway.

Gateway's existing `/api/products/**` route forwards uploads. The new
`catalog-images` route forwards public image GETs to Catalog without rewriting
their paths. Gateway permits anonymous GETs for `/uploads/catalog/**`, CORS
POST/GET/OPTIONS, and Authorization and Content-Type headers.
The image route removes downstream CORS origin/credentials headers so Gateway
supplies a single set rather than duplicating Catalog's direct-service headers.

Gateway `src/main/java/mss/mooncake/gateway/config/SecurityConfig.java` contains
this rule before `anyRequest()` for anonymous browser image loads:

```java
.requestMatchers(HttpMethod.GET, "/uploads/catalog/**").permitAll()
```

The installed Gateway MVC multipart resolver leaves routed multipart bodies
unparsed and forwards them to Catalog, which enforces the configured limits.

Focused checks:

```powershell
.\mvnw.cmd -pl CatalogStoreService '-Dtest=ProductImageServiceImplTest,ProductImageControllerTest' test
```

Frontend files: `src/features/catalog/productImageUpload.ts`,
`src/features/catalog/VariantImageField.tsx`,
`src/features/catalog/productImageUpload.test.tsx`,
`src/features/seller/ProductDetailPanel.tsx`, and
`src/features/seller/ProductManager.tsx`. Run from the frontend repository:

```powershell
node --import tsx --test src/features/catalog/productImageUpload.test.tsx
```

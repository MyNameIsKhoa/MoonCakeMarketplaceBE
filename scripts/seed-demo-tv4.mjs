// Seed tài khoản demo cho luồng TV4 (RFQ, báo giá, chat, chấp nhận báo giá → đơn + thanh toán TV5) qua API Gateway.
//
// Yêu cầu: Gateway + Identity + Catalog + Commerce + RFQ đang chạy, và đã chạy `node scripts/seed-demo-catalog.mjs`
// (tạo supplier VERIFIED + gian hàng ACTIVE + sản phẩm). Script này không cần quyền admin.
// Chạy lại được: buyer/địa chỉ đã có sẽ được dùng lại.
//
//   node scripts/seed-demo-tv4.mjs
//   GATEWAY_URL=http://localhost:8080 BUYER_PASSWORD=... SUPPLIER_PASSWORD=... node scripts/seed-demo-tv4.mjs
//
// Chỉ dùng cho demo local; mật khẩu mặc định in ra màn hình giống seed-demo-catalog.

const GATEWAY = (process.env.GATEWAY_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const BUYER_EMAIL = process.env.BUYER_EMAIL ?? 'buyer.b2b@mooncake.local';
const BUYER_PASSWORD = process.env.BUYER_PASSWORD ?? 'MoonCake@Buyer2026';
// Cùng mặc định với seed-demo-catalog.mjs.
const SUPPLIER_PASSWORD = process.env.SUPPLIER_PASSWORD ?? 'MoonCake@Supplier2026';
// Nhà cung cấp gửi báo giá, và một nhà cung cấp khác để demo không đọc được báo giá của đối thủ.
const SUPPLIERS = [
  { key: 'kinh-do', role: 'Nhà cung cấp gửi báo giá' },
  { key: 'givral', role: 'Nhà cung cấp khác (đối thủ)' }
];
const BUYER_ADDRESS = {
  recipientName: 'Phòng Hành chính - Công ty Demo B2B', recipientPhone: '0909000111',
  addressLine: '12 Tôn Đức Thắng', ward: 'Bến Nghé', district: 'Quận 1', city: 'TP. Hồ Chí Minh', isDefault: true
};

async function api(method, path, { body, token } = {}) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON body */ }
  return { status: res.status, json, text };
}

async function expect(promise, statuses, label) {
  const r = await promise;
  if (!statuses.includes(r.status)) {
    throw new Error(`${label} -> HTTP ${r.status} (mong đợi ${statuses.join('/')}): ${r.text}`);
  }
  return r.json;
}

async function login(email, password) {
  return (await expect(api('POST', '/api/auth/login', { body: { email, password } }), [200], `login ${email}`)).accessToken;
}

const roles = (token) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).roles ?? [];

// Chờ Gateway + RFQ sẵn sàng (GET /api/rfqs là public).
let ready = false;
for (let i = 0; i < 45 && !ready; i++) {
  try { ready = (await api('GET', '/api/rfqs?size=1')).status === 200; } catch { /* service still starting */ }
  if (!ready) await new Promise((resolve) => setTimeout(resolve, 2000));
}
if (!ready) throw new Error(`Gateway/RFQ chưa sẵn sàng sau 90 giây (${GATEWAY}).`);

// 1. Buyer doanh nghiệp + địa chỉ giao hàng (dùng khi chấp nhận báo giá).
const registered = await api('POST', '/api/auth/register', { body: { fullName: 'Công ty Demo B2B', email: BUYER_EMAIL, password: BUYER_PASSWORD } });
if (![200, 201, 409].includes(registered.status)) throw new Error(`register ${BUYER_EMAIL} -> HTTP ${registered.status}: ${registered.text}`);
const buyerToken = await login(BUYER_EMAIL, BUYER_PASSWORD);
let addresses = await expect(api('GET', '/api/addresses', { token: buyerToken }), [200], 'địa chỉ buyer');
if (addresses.length === 0) {
  addresses = [await expect(api('POST', '/api/addresses', { token: buyerToken, body: BUYER_ADDRESS }), [200, 201], 'tạo địa chỉ buyer')];
}
console.log(`OK    Buyer ${BUYER_EMAIL}: địa chỉ #${addresses[0].addressId}`);

// 2. Supplier từ seed-demo-catalog: phải VERIFIED, token có ROLE_SUPPLIER và có gian hàng ACTIVE để gửi báo giá.
const readySuppliers = [];
for (const s of SUPPLIERS) {
  const email = `supplier.${s.key}@mooncake.local`;
  const signedIn = await api('POST', '/api/auth/login', { body: { email, password: SUPPLIER_PASSWORD } });
  if (signedIn.status !== 200) {
    throw new Error(`Không đăng nhập được ${email} (HTTP ${signedIn.status}). Chạy \`node scripts/seed-demo-catalog.mjs\` trước.`);
  }
  const token = signedIn.json.accessToken;
  const profile = await expect(api('GET', '/api/supplier-profile', { token }), [200], `supplier profile ${s.key}`);
  if (profile.verificationStatus !== 'VERIFIED' || !roles(token).includes('ROLE_SUPPLIER')) {
    throw new Error(`${email} chưa là nhà cung cấp VERIFIED. Chạy lại \`node scripts/seed-demo-catalog.mjs\`.`);
  }
  const store = (await expect(api('GET', '/api/stores?mine=true', { token }), [200], `stores mine ${s.key}`))
    .find((st) => st.status === 'ACTIVE');
  if (!store) throw new Error(`${email} chưa có gian hàng ACTIVE. Chạy lại \`node scripts/seed-demo-catalog.mjs\`.`);
  readySuppliers.push({ ...s, email, store });
  console.log(`OK    ${s.role}: ${email}, gian hàng #${store.storeId} ${store.name}`);
}

const accounts = [['Buyer', BUYER_EMAIL, BUYER_PASSWORD], ...readySuppliers.map((s) => [s.role, s.email, SUPPLIER_PASSWORD])];
const width = Math.max(...accounts.map(([label]) => label.length));
console.log(`
Xong. Tài khoản demo TV4 (đăng nhập ở FE, tab Doanh Nghiệp):
${accounts.map(([label, email, password]) => `  ${label.padEnd(width)}  ${email} / ${password}`).join('\n')}
Mở buyer và nhà cung cấp ở hai cửa sổ trình duyệt riêng (một cửa sổ ẩn danh). Kịch bản: docs/TV4_BACKEND_GUIDE.md mục 9.`);

// Seed dữ liệu demo cho storefront (gian hàng, chi nhánh, sản phẩm, variant, khuyến mãi) qua API Gateway.
//
// Yêu cầu: Gateway + Identity + Catalog đang chạy; Identity khởi động với ADMIN_USER_IDS chứa userId của admin.
// Chạy lại được: supplier đã có gian hàng sẽ được bỏ qua.
//
//   node scripts/seed-demo-catalog.mjs
//   ADMIN_EMAIL=... ADMIN_PASSWORD=... GATEWAY_URL=http://localhost:8080 node scripts/seed-demo-catalog.mjs
//
// Ảnh là đường dẫn tương đối tới FE: MoonCakeMarketplaceFE/public/images/demo/...
// Dữ liệu chi nhánh/mô tả là dữ liệu minh họa, không phải thông tin thật của thương hiệu.

const GATEWAY = (process.env.GATEWAY_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_EMAIL = process.env.ADMIN_EMAIL ?? 'admin@mooncake.local';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? 'MoonCake@Admin2026';
const SUPPLIER_PASSWORD = process.env.SUPPLIER_PASSWORD ?? 'MoonCake@Supplier2026';
// Demo fixture only; each seller may edit its own flat fee through PATCH /api/stores/{id}.
const DEMO_SHIPPING_FEE = 30000;

const IMG = '/images/demo';
const BANNERS = ['nguyet-vien-banner.webp', 'trang-moc-banner.webp', 'sao-nho-banner.webp'];

/** product image file → public/images/demo/products/<file> */
const P = (file) => `${IMG}/products/${file}.webp`;
const BP = (file) => `${IMG}/brands/products/${file}.webp`;

const STORES = [
  {
    key: 'kinh-do', name: 'Kinh Đô', logo: 'logo.png',
    description: 'Bánh trung thu truyền thống quốc dân, hộp quà sang trọng cho gia đình và doanh nghiệp.',
    branch: { name: 'Kinh Đô Quận 1', addressLine: '72 Nguyễn Huệ', ward: 'Bến Nghé', district: 'Quận 1', city: 'TP. Hồ Chí Minh', openingHours: '08:00–21:00' },
    promotion: { name: 'Ưu đãi đặt sớm Trung Thu', discountPercent: 10 },
    products: [
      { key: 'TVHK', name: 'Hộp quà Trăng Vàng Hoàng Kim', category: 'cao-cap', image: P('hop-qua-trang-vang'),
        description: 'Hộp sơn mài 4 bánh nướng nhân cao cấp: bào ngư, yến sào, hạt sen, trà xanh.',
        variants: [['Hộp 4 bánh', 1250000, 40], ['Hộp 6 bánh', 1850000, 25]] },
      { key: 'TCGQ', name: 'Bánh nướng thập cẩm gà quay', category: 'truyen-thong', image: P('banh-nuong-thap-cam'),
        description: 'Nhân thập cẩm truyền thống với lạp xưởng, mỡ đường, hạt dưa và gà quay.',
        variants: [['Bánh 150g', 72000, 200], ['Bánh 250g', 115000, 120]] },
      { key: 'DHS', name: 'Bánh dẻo hạt sen', category: 'truyen-thong', image: P('banh-deo-hat-sen'),
        description: 'Vỏ dẻo thơm mùi hoa bưởi, nhân hạt sen sên mịn.',
        variants: [['Bánh 180g', 62000, 150]] }
    ]
  },
  {
    key: 'givral', name: 'Givral', logo: 'logo.png',
    description: 'Tiệm bánh lâu đời của Sài Gòn với hương vị bánh nướng đậm đà, nhân sên tay.',
    branch: { name: 'Givral Đồng Khởi', addressLine: '169 Đồng Khởi', ward: 'Bến Nghé', district: 'Quận 1', city: 'TP. Hồ Chí Minh', openingHours: '07:30–21:30' },
    promotion: { name: 'Giảm 8% cho đơn hộp quà', discountPercent: 8 },
    products: [
      { key: 'LVTM', name: 'Bánh lava trứng muối', category: 'hien-dai', image: P('banh-lava-trung-muoi'),
        description: 'Nhân custard trứng muối chảy, vỏ bánh mềm, dùng ngon nhất khi hâm nóng.',
        variants: [['Hộp 6 bánh mini', 390000, 60]] },
      { key: 'TCTT', name: 'Bánh nướng thập cẩm Givral', category: 'truyen-thong', image: P('banh-nuong-thap-cam'),
        description: 'Công thức thập cẩm Givral với vi cá, lạp xưởng và hạt điều.',
        variants: [['Bánh 150g', 85000, 150], ['Bánh 210g', 110000, 100]] },
      { key: 'NGQ', name: 'Hộp quà Givral Nguyệt Quang', category: 'cao-cap', image: P('hop-qua-trang-vang'),
        description: 'Hộp 4 bánh nhân hạt sen, đậu xanh, thập cẩm và trà xanh.',
        variants: [['Hộp 4 bánh', 980000, 30]] }
    ]
  },
  {
    key: 'dong-khanh', name: 'Đồng Khánh', logo: 'logo.png',
    description: 'Bánh trung thu Chợ Lớn, nổi tiếng với nhân thập cẩm và hương vị người Hoa.',
    branch: { name: 'Đồng Khánh Chợ Lớn', addressLine: '200 Trần Hưng Đạo', ward: 'Phường 11', district: 'Quận 5', city: 'TP. Hồ Chí Minh', openingHours: '08:00–21:00' },
    products: [
      { key: 'MTCC', name: 'Bánh nướng matcha cao cấp', category: 'hien-dai', image: P('banh-matcha-cao-cap'),
        description: 'Vỏ bánh trà xanh Uji, nhân đậu đỏ ít ngọt.',
        variants: [['Bánh 150g', 88000, 120]] },
      { key: 'TCLX', name: 'Bánh nướng thập cẩm lạp xưởng', category: 'truyen-thong', image: P('banh-nuong-thap-cam'),
        description: 'Nhân thập cẩm lạp xưởng truyền thống của Chợ Lớn.',
        variants: [['Bánh 200g', 95000, 150]] },
      { key: 'DV', name: 'Hộp quà Đồng Khánh Đoàn Viên', category: 'cao-cap', image: P('hop-qua-trang-vang'),
        description: 'Hộp quà 4 bánh kèm trà, phù hợp biếu tặng.',
        variants: [['Hộp 4 bánh', 820000, 35]] }
    ]
  },
  {
    key: 'hy-lam-mon', name: 'Hỷ Lâm Môn', logo: 'logo.png',
    description: 'Hỷ Lâm Môn Bakery từ 1984, bánh trung thu nướng thủ công theo công thức gia truyền.',
    branch: { name: 'Hỷ Lâm Môn Quận 10', addressLine: '412 Sư Vạn Hạnh', ward: 'Phường 9', district: 'Quận 10', city: 'TP. Hồ Chí Minh', openingHours: '07:00–21:00' },
    products: [
      { key: 'SDLD', name: 'Bánh nướng mini sữa dừa', category: 'thieu-nhi', image: P('banh-mini-sua-dua'),
        description: 'Bánh mini 50g nhân sữa dừa lá dứa, vừa miệng cho bé.',
        variants: [['Set 6 bánh', 180000, 80]] },
      { key: 'SCHN', name: 'Bánh socola hạnh nhân', category: 'hien-dai', image: P('banh-socola-hanh-nhan'),
        description: 'Vỏ cacao, nhân socola đen và hạnh nhân rang.',
        variants: [['Bánh 150g', 79000, 100]] },
      { key: 'CMD', name: 'Bánh chay mè đen', category: 'chay', image: P('banh-chay-me-den'),
        description: 'Bánh chay nhân mè đen thơm bùi, không dùng mỡ động vật.',
        variants: [['Bánh 150g', 65000, 90]] }
    ]
  },
  {
    key: 'duc-phat', name: 'Đức Phát', logo: 'logo.png',
    description: 'Đức Phát Bakery, thương hiệu bánh người Hoa với bánh nướng nhân jambon đặc trưng.',
    branch: { name: 'Đức Phát Quận 3', addressLine: '139 Hai Bà Trưng', ward: 'Võ Thị Sáu', district: 'Quận 3', city: 'TP. Hồ Chí Minh', openingHours: '07:00–21:00' },
    products: [
      { key: 'TCJB', name: 'Bánh nướng thập cẩm jambon', category: 'truyen-thong', image: P('banh-nuong-thap-cam'),
        description: 'Nhân thập cẩm kết hợp jambon, đậm vị truyền thống.',
        variants: [['Bánh 250g', 125000, 100]] },
      { key: 'MDT', name: 'Bánh mini dâu tây', category: 'thieu-nhi', image: P('banh-mini-dau-tay'),
        description: 'Bánh dẻo mini nhân mứt dâu tây, màu sắc tươi vui.',
        variants: [['Set 4 bánh', 160000, 80]] },
      { key: 'DHS', name: 'Bánh dẻo hạt sen Đức Phát', category: 'truyen-thong', image: P('banh-deo-hat-sen'),
        description: 'Bánh dẻo truyền thống nhân hạt sen.',
        variants: [['Bánh 180g', 68000, 100]] }
    ]
  },
  {
    key: 'abc', name: 'ABC Bakery', logo: 'logo.png',
    description: 'Bánh trung thu ABC với vị bánh nướng hiện đại và giá thân thiện.',
    branch: { name: 'ABC Bakery Phú Nhuận', addressLine: '98 Phan Đăng Lưu', ward: 'Phường 5', district: 'Phú Nhuận', city: 'TP. Hồ Chí Minh', openingHours: '07:00–22:00' },
    promotion: { name: 'Mùa trăng ABC giảm 5%', discountPercent: 5 },
    products: [
      { key: 'LVTM', name: 'Bánh lava trứng muối ABC', category: 'hien-dai', image: P('banh-lava-trung-muoi'),
        description: 'Nhân lava phô mai trứng muối.',
        variants: [['Hộp 6 bánh', 420000, 60]] },
      { key: 'TCTT', name: 'Bánh nướng thập cẩm ABC', category: 'truyen-thong', image: P('banh-nuong-thap-cam'),
        description: 'Thập cẩm truyền thống, ít ngọt.',
        variants: [['Bánh 150g', 68000, 150], ['Bánh 250g', 105000, 100]] },
      { key: 'MDT', name: 'Bánh mini dâu tây ABC', category: 'thieu-nhi', image: P('banh-mini-dau-tay'),
        description: 'Bánh mini cho bé, nhân dâu tây.',
        variants: [['Set 4 bánh', 150000, 80]] }
    ]
  },
  {
    key: 'khiet-minh', name: 'Khiết Minh', logo: 'logo.jpg',
    description: 'Bánh trung thu chay thanh tịnh, nguyên liệu thực vật, ít đường.',
    branch: { name: 'Khiết Minh Tân Bình', addressLine: '55 Trường Chinh', ward: 'Phường 12', district: 'Tân Bình', city: 'TP. Hồ Chí Minh', openingHours: '08:00–20:30' },
    products: [
      { key: 'CDX', name: 'Bánh chay đậu xanh', category: 'chay', image: P('banh-chay-dau-xanh'),
        description: 'Nhân đậu xanh sên tay, vỏ bánh dầu thực vật.',
        variants: [['Bánh 150g', 72000, 120]] },
      { key: 'CMD', name: 'Bánh chay mè đen', category: 'chay', image: P('banh-chay-me-den'),
        description: 'Nhân mè đen ít đường.',
        variants: [['Bánh 150g', 72000, 100]] },
      { key: 'MTC', name: 'Bánh chay matcha', category: 'chay', image: P('banh-matcha-cao-cap'),
        description: 'Bánh chay trà xanh nhân đậu đỏ.',
        variants: [['Bánh 150g', 78000, 90]] }
    ]
  },
  // Named-brand listings below are illustrative, not authorized brand storefronts.
  {
    key: 'nhu-lan', name: 'Như Lan (demo)', logoUrl: `${IMG}/brands/stores/demo-store-logo.svg`,
    bannerUrl: `${IMG}/brands/stores/nhu-lan-banner.webp`,
    description: 'Gian hàng tham chiếu để thử giao diện; ảnh, giá và người bán chỉ là dữ liệu mẫu.',
    products: [{ key: 'TC', name: 'Bánh nướng thập cẩm (demo)', category: 'truyen-thong',
      image: BP('nhu-lan-thap-cam'), description: 'Sản phẩm và giá minh họa.', variants: [['Bánh 150g minh họa', 155000, 50]] }]
  },
  {
    key: 'madame-huong', name: 'Madame Hương (demo)', logoUrl: `${IMG}/brands/stores/demo-store-logo.svg`,
    bannerUrl: `${IMG}/brands/stores/madame-huong-banner.webp`,
    description: 'Gian hàng tham chiếu, không đại diện nhà bán chính thức.',
    products: [{ key: 'CS', name: 'Bánh cốm sen (demo)', category: 'hien-dai',
      image: BP('madame-huong-com-sen'), description: 'Sản phẩm và giá minh họa.', variants: [['Bánh 150g minh họa', 165000, 45]] }]
  },
  {
    key: 'brodard', name: 'Brodard (demo)', logoUrl: `${IMG}/brands/stores/demo-store-logo.svg`,
    bannerUrl: `${IMG}/brands/stores/brodard-banner.webp`,
    description: 'Gian hàng tham chiếu, không đại diện nhà bán chính thức.',
    products: [{ key: 'HS', name: 'Bánh hải sản (demo)', category: 'cao-cap',
      image: BP('brodard-hai-san'), description: 'Sản phẩm và giá minh họa.', variants: [['Bánh 200g minh họa', 240000, 35]] }]
  },
  {
    key: 'kidos', name: "KIDO's Bakery (demo)", logoUrl: `${IMG}/brands/stores/demo-store-logo.svg`,
    bannerUrl: `${IMG}/brands/stores/kidos-banner.webp`,
    description: 'Gian hàng tham chiếu, không đại diện nhà bán chính thức.',
    products: [{ key: 'MTC', name: 'Bánh trà xanh phô mai (demo)', category: 'hien-dai',
      image: BP('kidos-tra-xanh-pho-mai'), description: 'Sản phẩm và giá minh họa.', variants: [['Bánh 150g minh họa', 160000, 50]] }]
  },
  {
    key: 'maison', name: 'Maison (demo)', logoUrl: `${IMG}/brands/stores/demo-store-logo.svg`,
    bannerUrl: `${IMG}/brands/stores/maison-banner.webp`,
    description: 'Gian hàng tham chiếu, không đại diện nhà bán chính thức.',
    products: [{ key: 'HQ', name: 'Hộp quà Trung thu (demo)', category: 'cao-cap',
      image: BP('maison-hop-qua'), description: 'Sản phẩm và giá minh họa.', variants: [['Hộp 4 bánh minh họa', 890000, 30]] }]
  }
];

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

const isoLocal = (d) => d.toISOString().slice(0, 19);

async function seedStore(def, index, adminToken, categoryIdBySlug) {
  const email = `supplier.${def.key}@mooncake.local`;

  const registered = await api('POST', '/api/auth/register', { body: { fullName: `${def.name} Supplier`, email, password: SUPPLIER_PASSWORD } });
  if (![200, 201, 409].includes(registered.status)) throw new Error(`register ${email} -> HTTP ${registered.status}: ${registered.text}`);
  let token = await login(email, SUPPLIER_PASSWORD);

  // Supplier profile → admin duyệt VERIFIED (bỏ qua nếu đã có/đã duyệt).
  let profile = await api('POST', '/api/supplier-profile', { token, body: { companyName: `Công ty ${def.name} (demo)`, taxCode: `DEMO-${def.key.toUpperCase()}` } });
  if (![200, 201].includes(profile.status)) {
    profile = await api('GET', '/api/supplier-profile', { token });
    if (profile.status !== 200) throw new Error(`supplier profile ${email} -> HTTP ${profile.status}: ${profile.text}`);
  }
  if (profile.json.verificationStatus !== 'VERIFIED') {
    await expect(api('POST', `/api/suppliers/${profile.json.supplierId}/verification`, { token: adminToken, body: { decision: 'VERIFIED', reason: 'Seed dữ liệu demo' } }), [200], `verify ${def.key}`);
    token = await login(email, SUPPLIER_PASSWORD);
  }

  const mine = await expect(api('GET', '/api/stores?mine=true', { token }), [200], `stores mine ${def.key}`);
  if (mine.length > 0) {
    if (mine[0].shippingFee == null) {
      await expect(api('PATCH', `/api/stores/${mine[0].storeId}`, {
        token, body: { shippingFee: DEMO_SHIPPING_FEE }
      }), [200], `shipping fee ${def.key}`);
    }
    console.log(`SKIP  ${def.name}: đã có gian hàng #${mine[0].storeId}`);
    return;
  }

  const store = await expect(api('POST', '/api/stores', {
    token,
    body: {
      name: def.name,
      description: def.description,
      logoUrl: def.logoUrl ?? `${IMG}/stores/${def.key}/${def.logo}`,
      bannerUrl: def.bannerUrl ?? `${IMG}/stores/${BANNERS[index % BANNERS.length]}`
    }
  }), [201], `tạo store ${def.key}`);
  await expect(api('PATCH', `/api/stores/${store.storeId}`, {
    token, body: { status: 'ACTIVE', shippingFee: DEMO_SHIPPING_FEE }
  }), [200], `store ${def.key} ACTIVE`);
  if (def.branch) await expect(api('POST', `/api/stores/${store.storeId}/branches`, { token, body: { ...def.branch, primary: true } }), [201, 200], `chi nhánh ${def.key}`);

  for (const p of def.products) {
    const categoryId = categoryIdBySlug.get(p.category);
    if (!categoryId) throw new Error(`Không có category slug '${p.category}' (migration V3 chưa seed?)`);
    const product = await expect(api('POST', '/api/products', {
      token, body: { storeId: store.storeId, categoryId, name: p.name, description: p.description }
    }), [201], `product ${p.name}`);
    for (const [i, [variantName, basePrice, stockQuantity]] of p.variants.entries()) {
      const variant = await expect(api('POST', `/api/products/${product.productId}/variants`, {
        token,
        body: { sku: `${def.key.toUpperCase()}-${p.key}-${i + 1}`, variantName, basePrice, stockQuantity, imageUrl: p.image }
      }), [201], `variant ${p.name} / ${variantName}`);
      await expect(api('PATCH', `/api/products/${product.productId}/variants/${variant.variantId}`, { token, body: { status: 'ACTIVE' } }), [200], `variant ${variantName} ACTIVE`);
    }
    await expect(api('PATCH', `/api/products/${product.productId}`, { token, body: { status: 'ACTIVE' } }), [200], `product ${p.name} ACTIVE`);
  }

  if (def.promotion) {
    const now = new Date();
    const promotion = await expect(api('POST', '/api/promotions', {
      token,
      body: {
        storeId: store.storeId,
        name: def.promotion.name,
        startAt: isoLocal(new Date(now.getTime() - 86400000)),
        endAt: isoLocal(new Date(now.getTime() + 45 * 86400000)),
        rules: [{ ruleType: 'PERCENT_DISCOUNT', discountPercent: def.promotion.discountPercent }]
      }
    }), [201], `promotion ${def.key}`);
    await expect(api('PATCH', `/api/promotions/${promotion.promotionId}`, { token, body: { status: 'ACTIVE' } }), [200], `promotion ${def.key} ACTIVE`);
  }

  console.log(`OK    ${def.name}: store #${store.storeId}, ${def.products.length} sản phẩm${def.promotion ? ', 1 khuyến mãi' : ''}`);
}

const adminToken = await login(ADMIN_EMAIL, ADMIN_PASSWORD);
const categories = await expect(api('GET', '/api/categories?size=100'), [200], 'categories');
const categoryIdBySlug = new Map(categories.items.map((c) => [c.slug, c.categoryId]));

for (const [index, def] of STORES.entries()) {
  await seedStore(def, index, adminToken, categoryIdBySlug);
}
console.log(`\nXong. Supplier đăng nhập bằng supplier.<key>@mooncake.local / ${SUPPLIER_PASSWORD}`);

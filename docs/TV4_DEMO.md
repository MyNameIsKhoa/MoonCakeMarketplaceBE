# Demo TV4 — RFQ, báo giá, chat

Luồng demo: người mua đăng yêu cầu báo giá (gửi tất cả hoặc chọn cửa hàng) → nhà cung cấp báo giá, sửa phiên bản → hai bên chat → người mua chấp nhận → Commerce tạo đơn, TV5 tạo khoản thanh toán.

Chi tiết nghiệp vụ và API: [TV4_BACKEND_GUIDE.md](TV4_BACKEND_GUIDE.md).

## 1. Chuẩn bị

**Backend** (repo BE, `.env` đã có đủ khóa):

```powershell
$env:ADMIN_USER_IDS="1"; docker compose -p mooncake-demo up -d
```

**Dữ liệu demo** (chạy một lần, chạy lại không tạo trùng):

```powershell
node scripts/seed-demo-catalog.mjs
```

```powershell
node scripts/seed-demo-tv4.mjs
```

**Frontend** (repo FE). Cổng 3000 trên máy đang bị dự án khác dùng nên chạy cổng 5173:

```powershell
$env:PORT="5173"; npm run dev
```

Mở `http://localhost:5173`. Nếu báo lỗi cổng 24678 thì thêm `$env:DISABLE_HMR="true";` vào đầu lệnh.

## 2. Tài khoản

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Người mua | `buyer.b2b@mooncake.local` | `MoonCake@Buyer2026` |
| Nhà cung cấp | `supplier.kinh-do@mooncake.local` | `MoonCake@Supplier2026` |
| Nhà cung cấp khác | `supplier.givral@mooncake.local` | `MoonCake@Supplier2026` |

Mở người mua và nhà cung cấp ở **hai cửa sổ riêng** (một cửa sổ ẩn danh), vì phiên đăng nhập dùng chung trong một trình duyệt.

## 3. Kịch bản (~10 phút)

| # | Ai | Thao tác | Điểm nhấn |
|---|---|---|---|
| 1 | Người mua | Tab **Doanh Nghiệp** → **Tạo yêu cầu báo giá** → mục **Gửi đến** chọn **Chọn cửa hàng** → tích **Kinh Đô** → **Đăng yêu cầu** | Gửi riêng: không lên sàn chung. Chọn "Tất cả cửa hàng" thì lên sàn như bình thường |
| 2 | Givral | Đăng nhập → **Sàn yêu cầu báo giá** | Không thấy RFQ vừa đăng (không được mời) |
| 3 | Kinh Đô | Sàn có RFQ với nhãn **"Gửi riêng cho cửa hàng của bạn"** → mở → **Gửi báo giá** (cọc 30%, đơn giá, phí ship) | Chỉ chọn được cửa hàng được mời; tiền là VND nguyên |
| 4 | Kinh Đô | Sửa báo giá → phiên bản 2 với giá mới | Bản cũ giữ nguyên giá, chuyển "Đã có phiên bản mới" |
| 5 | Người mua | Mở RFQ → **So sánh báo giá** → **Xem chi tiết** | Thấy danh sách cửa hàng đã mời |
| 6 | Cả hai | Người mua **Nhắn tin với nhà cung cấp**, hai bên nhắn qua lại | Chat thời gian thực, tin hiện ngay ở cửa sổ kia |
| 7 | Người mua | **Chấp nhận** phiên bản 2 → chọn/thêm địa chỉ | Hiện "Đơn hàng #… (đơn nhà bán #…)" |
| 8 | Người mua | Tab **Thanh Toán** → nhập **mã đơn nhà bán** | Có **Tiền cọc** + **Phần còn lại** (hạn 3 ngày trước ngày giao); cọc 0% hoặc 100% thì một khoản toàn bộ |

Demo thêm nếu còn thời gian: người mua từ chối báo giá, nhà cung cấp rút báo giá, nhập số tiền lẻ đồng để thấy lỗi.

## 4. Lưu ý

- Nút **Tạo link thanh toán** cần khóa `PAYOS_*` trong `.env`; không có thì chỉ demo tới danh sách khoản thanh toán.
- API trả giờ theo UTC (`17:00Z` = 00:00 giờ Việt Nam hôm sau).
- Mỗi lần demo tạo RFQ/đơn mới trong database, không tự xóa.

| Lỗi | Nguyên nhân |
|---|---|
| Không thấy cửa hàng để báo giá | Cửa hàng không được mời hoặc chưa `ACTIVE` |
| `403 STORE_NOT_TARGETED` | Báo giá từ cửa hàng không nằm trong danh sách mời |
| `403 SUPPLIER_NOT_TARGETED` | Người mua nhắn tin với nhà cung cấp không được mời |
| Seed TV4 báo "chạy seed-demo-catalog trước" | Chưa có supplier/cửa hàng demo |

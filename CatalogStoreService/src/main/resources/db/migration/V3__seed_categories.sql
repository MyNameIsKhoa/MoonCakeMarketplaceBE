-- TV2: danh mục gốc dùng chung toàn sàn (CA01). Slug khớp category của FE (types.ts).
INSERT INTO dbo.category (parent_category_id, name, slug, status) VALUES
    (NULL, N'Bánh truyền thống', N'truyen-thong', N'ACTIVE'),
    (NULL, N'Bánh hiện đại',     N'hien-dai',     N'ACTIVE'),
    (NULL, N'Bánh chay',         N'chay',         N'ACTIVE'),
    (NULL, N'Bánh cao cấp',      N'cao-cap',      N'ACTIVE'),
    (NULL, N'Bánh thiếu nhi',    N'thieu-nhi',    N'ACTIVE');

-- Demo e-commerce database for trial users
-- Run against the demo tenant's database after creation

-- Customers
CREATE TABLE IF NOT EXISTS customers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    phone VARCHAR(20),
    city VARCHAR(50),
    created_at TIMESTAMP DEFAULT now()
);

-- Products
CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INTEGER DEFAULT 0,
    description TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Orders
CREATE TABLE IF NOT EXISTS orders (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    total DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT now()
);

-- Order items
CREATE TABLE IF NOT EXISTS order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER REFERENCES orders(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER NOT NULL,
    price DECIMAL(10,2) NOT NULL
);

-- Reviews
CREATE TABLE IF NOT EXISTS reviews (
    id SERIAL PRIMARY KEY,
    product_id INTEGER REFERENCES products(id),
    customer_id INTEGER REFERENCES customers(id),
    rating INTEGER CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Enable vector extension for demo
CREATE EXTENSION IF NOT EXISTS vector;

-- Product embeddings (demonstrate vector capability)
ALTER TABLE products ADD COLUMN IF NOT EXISTS embedding vector(3);

-- ===== Sample Data =====

-- Customers (20)
INSERT INTO customers (name, email, phone, city) VALUES
('张三', 'zhangsan@example.com', '13800001001', '北京'),
('李四', 'lisi@example.com', '13800001002', '上海'),
('王五', 'wangwu@example.com', '13800001003', '深圳'),
('赵六', 'zhaoliu@example.com', '13800001004', '广州'),
('孙七', 'sunqi@example.com', '13800001005', '杭州'),
('周八', 'zhouba@example.com', '13800001006', '成都'),
('吴九', 'wujiu@example.com', '13800001007', '武汉'),
('郑十', 'zhengshi@example.com', '13800001008', '南京'),
('陈小明', 'chenxm@example.com', '13800001009', '西安'),
('林小红', 'linxh@example.com', '13800001010', '重庆'),
('黄大伟', 'huangdw@example.com', '13800001011', '天津'),
('刘美丽', 'liuml@example.com', '13800001012', '苏州'),
('杨建国', 'yangjg@example.com', '13800001013', '长沙'),
('朱文静', 'zhuwj@example.com', '13800001014', '青岛'),
('谢志强', 'xiezq@example.com', '13800001015', '大连'),
('马小龙', 'maxl@example.com', '13800001016', '厦门'),
('何丽华', 'helh@example.com', '13800001017', '合肥'),
('罗明辉', 'luomh@example.com', '13800001018', '郑州'),
('韩雪', 'hanxue@example.com', '13800001019', '昆明'),
('方圆', 'fangyuan@example.com', '13800001020', '福州')
ON CONFLICT (email) DO NOTHING;

-- Products (30)
INSERT INTO products (name, category, price, stock, description, embedding) VALUES
('MacBook Pro 16"', '电子产品', 18999.00, 50, 'Apple M3 Pro 芯片，18GB 内存，512GB 存储', '[0.1, 0.8, 0.3]'),
('iPhone 16 Pro', '电子产品', 8999.00, 200, 'A18 Pro 芯片，256GB，深空黑', '[0.15, 0.85, 0.25]'),
('AirPods Pro 2', '电子产品', 1899.00, 500, '主动降噪，自适应音频', '[0.12, 0.7, 0.4]'),
('iPad Air', '电子产品', 4799.00, 150, 'M2 芯片，10.9 英寸', '[0.13, 0.75, 0.35]'),
('Sony WH-1000XM5', '电子产品', 2499.00, 100, '无线降噪耳机，30小时续航', '[0.11, 0.65, 0.45]'),
('三体（全三册）', '图书', 89.00, 1000, '刘慈欣科幻巨著', '[0.8, 0.1, 0.5]'),
('算法导论', '图书', 128.00, 300, '经典计算机科学教材', '[0.85, 0.15, 0.6]'),
('小王子', '图书', 32.00, 800, '圣埃克苏佩里经典童话', '[0.75, 0.05, 0.55]'),
('人类简史', '图书', 68.00, 600, '尤瓦尔·赫拉利著', '[0.78, 0.08, 0.52]'),
('代码大全', '图书', 148.00, 200, '软件构造实用手册', '[0.88, 0.18, 0.65]'),
('北欧风落地灯', '家居', 399.00, 80, '简约设计，暖光 LED', '[0.3, 0.3, 0.8]'),
('记忆棉枕头', '家居', 199.00, 300, '慢回弹太空棉，护颈设计', '[0.25, 0.25, 0.85]'),
('陶瓷餐具套装', '家居', 259.00, 150, '日式简约风，6人份', '[0.28, 0.28, 0.82]'),
('智能加湿器', '家居', 189.00, 200, '静音设计，4L 大容量', '[0.22, 0.32, 0.78]'),
('收纳架三层', '家居', 129.00, 400, '免打孔安装，不锈钢材质', '[0.2, 0.35, 0.75]'),
('云南普洱茶饼', '食品', 168.00, 500, '2020年古树春茶，357g', '[0.5, 0.5, 0.2]'),
('有机坚果礼盒', '食品', 128.00, 300, '混合坚果 6 种，750g', '[0.48, 0.52, 0.18]'),
('意式浓缩咖啡豆', '食品', 89.00, 400, '中深度烘焙，500g', '[0.52, 0.48, 0.22]'),
('日本抹茶粉', '食品', 78.00, 250, '宇治抹茶，100g', '[0.45, 0.55, 0.15]'),
('新西兰蜂蜜', '食品', 158.00, 180, 'UMF 10+ 麦卢卡蜂蜜', '[0.55, 0.45, 0.25]'),
('纯棉 T 恤', '服装', 99.00, 600, '精梳棉，圆领基础款', '[0.6, 0.2, 0.6]'),
('运动跑鞋', '服装', 499.00, 200, '缓震回弹，透气网面', '[0.65, 0.22, 0.58]'),
('羊毛围巾', '服装', 259.00, 150, '100% 美利奴羊毛', '[0.58, 0.18, 0.62]'),
('牛仔外套', '服装', 399.00, 100, '复古水洗，纯棉面料', '[0.62, 0.24, 0.56]'),
('帆布双肩包', '服装', 179.00, 300, '防水涂层，大容量', '[0.55, 0.2, 0.65]'),
('机械键盘', '电子产品', 599.00, 250, 'Cherry 红轴，RGB 背光', '[0.14, 0.72, 0.38]'),
('4K 显示器 27"', '电子产品', 2299.00, 80, 'IPS 面板，Type-C 一线连', '[0.16, 0.82, 0.28]'),
('无线充电板', '电子产品', 129.00, 400, 'Qi 15W 快充', '[0.1, 0.6, 0.5]'),
('移动硬盘 2TB', '电子产品', 459.00, 300, 'USB 3.2，读速 1050MB/s', '[0.18, 0.78, 0.32]'),
('智能手表', '电子产品', 1599.00, 180, '血氧检测，GPS 定位', '[0.17, 0.8, 0.3]')
ON CONFLICT DO NOTHING;

-- Orders (40)
INSERT INTO orders (customer_id, total, status, created_at) VALUES
(1, 18999.00, 'completed', now() - interval '30 days'),
(1, 89.00, 'completed', now() - interval '25 days'),
(2, 8999.00, 'completed', now() - interval '28 days'),
(2, 259.00, 'shipped', now() - interval '3 days'),
(3, 1899.00, 'completed', now() - interval '20 days'),
(3, 399.00, 'completed', now() - interval '15 days'),
(4, 4799.00, 'completed', now() - interval '22 days'),
(4, 168.00, 'pending', now() - interval '1 day'),
(5, 2499.00, 'shipped', now() - interval '5 days'),
(5, 128.00, 'completed', now() - interval '18 days'),
(6, 599.00, 'completed', now() - interval '14 days'),
(6, 199.00, 'completed', now() - interval '10 days'),
(7, 2299.00, 'shipped', now() - interval '4 days'),
(7, 32.00, 'completed', now() - interval '12 days'),
(8, 129.00, 'completed', now() - interval '8 days'),
(8, 499.00, 'shipped', now() - interval '2 days'),
(9, 89.00, 'completed', now() - interval '16 days'),
(9, 1599.00, 'pending', now() - interval '1 day'),
(10, 399.00, 'completed', now() - interval '11 days'),
(10, 78.00, 'completed', now() - interval '7 days'),
(11, 8999.00, 'completed', now() - interval '26 days'),
(12, 148.00, 'completed', now() - interval '19 days'),
(13, 259.00, 'shipped', now() - interval '3 days'),
(14, 189.00, 'completed', now() - interval '9 days'),
(15, 18999.00, 'completed', now() - interval '24 days'),
(16, 158.00, 'completed', now() - interval '6 days'),
(17, 459.00, 'shipped', now() - interval '2 days'),
(18, 68.00, 'completed', now() - interval '13 days'),
(19, 1899.00, 'completed', now() - interval '17 days'),
(20, 99.00, 'pending', now()),
(1, 599.00, 'pending', now()),
(3, 128.00, 'shipped', now() - interval '2 days'),
(5, 179.00, 'completed', now() - interval '6 days'),
(7, 259.00, 'pending', now()),
(9, 399.00, 'shipped', now() - interval '1 day'),
(11, 129.00, 'completed', now() - interval '4 days'),
(13, 2499.00, 'pending', now()),
(15, 89.00, 'completed', now() - interval '8 days'),
(17, 1599.00, 'shipped', now() - interval '3 days'),
(19, 499.00, 'completed', now() - interval '5 days')
ON CONFLICT DO NOTHING;

-- Order items (60)
INSERT INTO order_items (order_id, product_id, quantity, price) VALUES
(1, 1, 1, 18999.00), (2, 6, 1, 89.00), (3, 2, 1, 8999.00),
(4, 13, 1, 259.00), (5, 3, 1, 1899.00), (6, 11, 1, 399.00),
(7, 4, 1, 4799.00), (8, 16, 1, 168.00), (9, 5, 1, 2499.00),
(10, 17, 1, 128.00), (11, 26, 1, 599.00), (12, 12, 1, 199.00),
(13, 27, 1, 2299.00), (14, 8, 1, 32.00), (15, 15, 1, 129.00),
(16, 22, 1, 499.00), (17, 18, 1, 89.00), (18, 30, 1, 1599.00),
(19, 24, 1, 399.00), (20, 19, 1, 78.00), (21, 2, 1, 8999.00),
(22, 10, 1, 148.00), (23, 23, 1, 259.00), (24, 14, 1, 189.00),
(25, 1, 1, 18999.00), (26, 20, 1, 158.00), (27, 29, 1, 459.00),
(28, 9, 1, 68.00), (29, 3, 1, 1899.00), (30, 21, 1, 99.00),
(31, 26, 1, 599.00), (32, 17, 1, 128.00), (33, 25, 1, 179.00),
(34, 23, 1, 259.00), (35, 11, 1, 399.00), (36, 28, 1, 129.00),
(37, 5, 1, 2499.00), (38, 18, 1, 89.00), (39, 30, 1, 1599.00),
(40, 22, 1, 499.00),
(1, 3, 1, 1899.00), (3, 3, 1, 1899.00), (7, 3, 1, 1899.00),
(11, 28, 2, 129.00), (15, 28, 1, 129.00), (21, 3, 1, 1899.00),
(25, 3, 1, 1899.00), (5, 28, 1, 129.00), (9, 12, 1, 199.00),
(13, 8, 2, 32.00), (17, 19, 1, 78.00), (19, 21, 2, 99.00),
(2, 8, 1, 32.00), (4, 19, 1, 78.00), (6, 12, 1, 199.00),
(8, 20, 1, 158.00), (10, 18, 2, 89.00), (12, 15, 1, 129.00),
(14, 9, 1, 68.00), (16, 21, 1, 99.00)
ON CONFLICT DO NOTHING;

-- Reviews (30)
INSERT INTO reviews (product_id, customer_id, rating, comment, created_at) VALUES
(1, 1, 5, '性能强劲，编译速度飞快，非常满意', now() - interval '28 days'),
(1, 15, 5, '屏幕显示效果一流，续航也很好', now() - interval '22 days'),
(2, 2, 4, '拍照效果很好，就是有点贵', now() - interval '26 days'),
(2, 11, 5, '手感好，速度快，值得升级', now() - interval '24 days'),
(3, 3, 5, '降噪效果非常好，通勤必备', now() - interval '18 days'),
(4, 4, 4, '轻薄便携，办公很方便', now() - interval '20 days'),
(5, 5, 5, '音质超好，降噪强，佩戴舒适', now() - interval '16 days'),
(6, 1, 5, '三体是神作，百看不厌', now() - interval '23 days'),
(6, 2, 5, '硬科幻巅峰，推荐所有人', now() - interval '15 days'),
(7, 12, 4, '经典教材，内容全面但有点厚', now() - interval '17 days'),
(8, 7, 5, '送给孩子的礼物，插画很美', now() - interval '10 days'),
(9, 18, 4, '视角很宏大，翻译也不错', now() - interval '11 days'),
(10, 12, 5, '程序员必读，实用性很强', now() - interval '17 days'),
(11, 3, 4, '灯光很柔和，就是底座有点大', now() - interval '13 days'),
(12, 6, 5, '睡眠质量明显改善，推荐', now() - interval '8 days'),
(13, 4, 4, '质感不错，很有日系风格', now() - interval '9 days'),
(16, 8, 5, '茶味醇厚，回甘明显', now() - interval '6 days'),
(17, 10, 4, '坚果新鲜，包装精美', now() - interval '5 days'),
(18, 9, 5, '咖啡香气浓郁，手冲效果好', now() - interval '14 days'),
(22, 8, 4, '缓震不错，跑步很舒服', now() - interval '7 days'),
(26, 6, 5, '手感好，打字声音清脆', now() - interval '12 days'),
(26, 11, 4, '灯效很炫，做工扎实', now() - interval '2 days'),
(27, 7, 5, '色彩准确，Type-C 很方便', now() - interval '3 days'),
(29, 17, 4, '传输速度很快，外观小巧', now() - interval '1 day'),
(30, 9, 5, '功能齐全，续航一周', now() - interval '4 days'),
(30, 18, 4, '运动检测准确，性价比高', now() - interval '2 days'),
(21, 20, 3, '纯棉舒服但容易起球', now() - interval '5 days'),
(24, 19, 4, '复古好看，就是有点硬', now() - interval '6 days'),
(25, 5, 5, '容量大，背着很舒服', now() - interval '4 days'),
(14, 14, 4, '很安静，加湿效果好', now() - interval '7 days')
ON CONFLICT DO NOTHING;

-- Create useful indexes for demo queries
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items(product_id);
CREATE INDEX IF NOT EXISTS idx_reviews_product ON reviews(product_id);
CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);

-- Create a view for common analytics query
CREATE OR REPLACE VIEW product_sales_summary AS
SELECT
    p.id AS product_id,
    p.name AS product_name,
    p.category,
    p.price,
    COUNT(DISTINCT oi.order_id) AS order_count,
    SUM(oi.quantity) AS total_sold,
    ROUND(AVG(r.rating), 1) AS avg_rating,
    COUNT(DISTINCT r.id) AS review_count
FROM products p
LEFT JOIN order_items oi ON oi.product_id = p.id
LEFT JOIN reviews r ON r.product_id = p.id
GROUP BY p.id, p.name, p.category, p.price;

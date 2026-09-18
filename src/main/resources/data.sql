INSERT INTO users (username, password, role) VALUES ('admin', 'admin123', 'ADMIN')
  ON CONFLICT DO NOTHING;
INSERT INTO users (username, password, role) VALUES ('alice', 'alice123', 'USER')
  ON CONFLICT DO NOTHING;

INSERT INTO products (name, price, stock) VALUES ('機械鍵盤', 2999.99, 10);
INSERT INTO products (name, price, stock) VALUES ('人體工學椅', 8800.00, 5);
INSERT INTO products (name, price, stock) VALUES ('4K 螢幕', 12000.00, 3);

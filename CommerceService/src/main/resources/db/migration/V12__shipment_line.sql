-- A shipment (batch) carries part of its seller order: which order items and how many units.
-- Shipments created before this migration have no lines; they keep the old "whole order" delivery rule.
CREATE TABLE dbo.shipment_line (
    shipment_line_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_shipment_line PRIMARY KEY,
    shipment_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT CK_shipment_line_quantity CHECK (quantity > 0),
    CONSTRAINT UQ_shipment_line_item UNIQUE (shipment_id, order_item_id),
    CONSTRAINT FK_shipment_line_shipment FOREIGN KEY (shipment_id) REFERENCES dbo.shipment(shipment_id),
    CONSTRAINT FK_shipment_line_order_item FOREIGN KEY (order_item_id) REFERENCES dbo.order_item(order_item_id)
);

CREATE INDEX IX_shipment_line_order_item ON dbo.shipment_line(order_item_id);

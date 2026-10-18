-- V44: журнал действий администратора (patches/INTEGRATION_ADMIN.md).
-- Кто, когда, что сделал, над чем и с каким результатом. Персональных данных в строке нет:
-- объект — тип и числовой id (после удаления аккаунта id остаётся, e-mail — нет), details —
-- служебные пояснения без адресов и имён.
CREATE TABLE admin_audit_log (
    id            BIGSERIAL    PRIMARY KEY,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    admin_user_id BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    action        VARCHAR(50)  NOT NULL,
    target_type   VARCHAR(30),
    target_id     BIGINT,
    result        VARCHAR(20)  NOT NULL,
    details       VARCHAR(500)
);
CREATE INDEX idx_admin_audit_created ON admin_audit_log(created_at DESC);
CREATE INDEX idx_admin_audit_target ON admin_audit_log(target_type, target_id);

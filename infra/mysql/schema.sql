-- Esquema do MySQL LEGADO (só notificações), usado no ambiente de desenvolvimento. Relações (social_graph_service) e
-- postagens, curtidas e imagens (post_service) já migraram e saíram daqui.
--
-- A tabela `usuario` é uma PROJEÇÃO do perfil: a fonte da verdade é o profile_service (PostgreSQL), que grava aqui, por
-- eventos (outbox -> RabbitMQ), SOMENTE o que os JOINs do legado precisam. Por isso NÃO tem e-mail, senha, telefone, sexo
-- nem data de nascimento (esses dados ficam no authentication_service/profile_service). O backend Javalin só LÊ esta tabela.
-- Quando Notificações migrar, a tabela e este arquivo deixam de existir.
--
-- ATENÇÃO: o esquema de `notification` foi INFERIDO das consultas SQL do backend Javalin. Este arquivo só roda na CRIAÇÃO
-- do volume: num volume antigo (com relacao/postagem/curtida e notification.id_post INT), recrie o volume (sem dados reais):
--   docker compose rm -sf mysql && docker volume rm social-flux_mysql_data && docker compose up -d

CREATE TABLE IF NOT EXISTS usuario (
    id               INT PRIMARY KEY,            -- = profile.legacy_id (alocado pelo profile_service); sem AUTO_INCREMENT
    username         VARCHAR(60)  NOT NULL UNIQUE,
    nome             VARCHAR(120) NOT NULL,
    biografia        TEXT         NULL,
    foto_perfil      VARCHAR(255) NULL,          -- chave do avatar (av_<uuid>.jpg), servido pelo profile_service
    privado          BOOLEAN      NOT NULL DEFAULT FALSE,
    data_criacao     DATE         NOT NULL,
    profile_version  BIGINT       NOT NULL DEFAULT 0   -- versão do evento aplicado (ignora eventos antigos/duplicados)
);

CREATE TABLE IF NOT EXISTS notification (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    description  TEXT         NULL,
    id_sender    INT          NOT NULL,
    id_receiver  INT          NULL,
    id_category  INT          NOT NULL,
    id_post      VARCHAR(36)  NULL,             -- UUID da postagem no post_service
    viewed       BOOLEAN      NOT NULL DEFAULT FALSE,
    FOREIGN KEY (id_sender)   REFERENCES usuario (id) ON DELETE CASCADE,
    FOREIGN KEY (id_receiver) REFERENCES usuario (id) ON DELETE CASCADE
);

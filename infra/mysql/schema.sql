-- Esquema do MySQL LEGADO (relações, postagens, curtidas, notificações), usado no ambiente de desenvolvimento.
--
-- A tabela `usuario` é uma PROJEÇÃO do perfil: a fonte da verdade é o profile_service (PostgreSQL), que grava aqui, por
-- eventos (outbox -> RabbitMQ), SOMENTE o que os JOINs do legado precisam. Por isso NÃO tem e-mail, senha, telefone, sexo
-- nem data de nascimento (esses dados ficam no authentication_service/profile_service). O backend Javalin só LÊ esta tabela.
-- Quando Relações, Postagens e Notificações migrarem, a tabela e este arquivo deixam de existir.
--
-- ATENÇÃO: o esquema das demais tabelas foi INFERIDO das consultas SQL do backend Javalin.
-- Para um volume MySQL criado com o esquema antigo (usuario com email/senha...), recrie o volume (não há dados reais):
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

CREATE TABLE IF NOT EXISTS relacao (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    id_seguidor  INT     NOT NULL,
    id_seguido   INT     NOT NULL,
    aceito       BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (id_seguidor, id_seguido),
    FOREIGN KEY (id_seguidor) REFERENCES usuario (id) ON DELETE CASCADE,
    FOREIGN KEY (id_seguido)  REFERENCES usuario (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS postagem (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    imagem         VARCHAR(255) NULL,
    legenda        TEXT         NULL,
    data_postagem  DATETIME     NOT NULL,
    id_usuario     INT          NOT NULL,
    id_postagem    INT          NULL,
    FOREIGN KEY (id_usuario)  REFERENCES usuario (id)  ON DELETE CASCADE,
    FOREIGN KEY (id_postagem) REFERENCES postagem (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS curtida (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario   INT NOT NULL,
    id_conteudo  INT NOT NULL,
    UNIQUE (id_usuario, id_conteudo),
    FOREIGN KEY (id_usuario)  REFERENCES usuario (id)  ON DELETE CASCADE,
    FOREIGN KEY (id_conteudo) REFERENCES postagem (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS notification (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    description  TEXT         NULL,
    id_sender    INT          NOT NULL,
    id_receiver  INT          NULL,
    id_category  INT          NOT NULL,
    id_post      INT          NULL,
    viewed       BOOLEAN      NOT NULL DEFAULT FALSE,
    FOREIGN KEY (id_sender)   REFERENCES usuario (id) ON DELETE CASCADE,
    FOREIGN KEY (id_receiver) REFERENCES usuario (id) ON DELETE CASCADE
);

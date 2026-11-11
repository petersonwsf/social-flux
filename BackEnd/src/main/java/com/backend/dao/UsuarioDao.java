package com.backend.dao;

import com.backend.model.entities.Usuario;

import java.sql.*;

public class UsuarioDao {

    private Connection connectionSQL;

    public UsuarioDao(Connection connection) {
        this.connectionSQL = connection;
    }

    public ResultSet buscarRelacoesEmComum(Integer id, Integer id_usuario_perfil) throws SQLException {

        String sql = "SELECT u.id, u.username, u.nome, u.foto_perfil, u.privado, u.biografia " +
                "FROM usuario u " +
                "JOIN relacao relacao_usuario ON u.id = relacao_usuario.id_seguido " +
                "JOIN relacao relacao_usuario_perfil ON u.id = relacao_usuario_perfil.id_seguidor " +
                "WHERE relacao_usuario.aceito = 1 AND relacao_usuario_perfil.aceito = 1 " +
                "AND relacao_usuario.id_seguidor = ? " +
                "AND relacao_usuario_perfil.id_seguido = ? " +
                "AND u.id != ?";

        PreparedStatement preparedStatement = connectionSQL.prepareStatement(sql);
        preparedStatement.setInt(1, id);
        preparedStatement.setInt(2, id_usuario_perfil);
        preparedStatement.setInt(3, id);

        ResultSet resultSet = preparedStatement.executeQuery();
        if (resultSet.next()) return resultSet;
        return null;
    }
}

package com.backend.services;

import com.backend.dao.UsuarioDao;
import com.backend.model.entities.Usuario;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class UsuarioService {

    private static final Logger logger = LogManager.getLogger();
    private final UsuarioDao usuarioDao;

    public UsuarioService(Connection connection) {
        this.usuarioDao = new UsuarioDao(connection);
    }

    public List<Usuario> buscarRelacoesEmComum(Integer id, Integer id_usuario_perfil) {
        List<Usuario> listaUsuarios = new ArrayList<>();
        try (ResultSet resultSet = usuarioDao.buscarRelacoesEmComum(id, id_usuario_perfil)) {
            if (resultSet == null) return listaUsuarios;
            do {
                listaUsuarios.add(resultSetToUsuario(resultSet));
            } while (resultSet.next());

            return listaUsuarios;
        } catch (SQLException e) {
            logger.error("Erro ao buscar relações em comum: " + e);
            throw new RuntimeException(e);
        }
    }

    private Usuario resultSetToUsuario(ResultSet resultSet) throws SQLException {
        Usuario usuario = new Usuario();
        usuario.setId(resultSet.getInt("id"));
        usuario.setUsername(resultSet.getString("username"));
        usuario.setNome(resultSet.getString("nome"));
        usuario.setPrivado(resultSet.getBoolean("privado"));
        usuario.setBiografia(resultSet.getString("biografia"));
        usuario.setFoto_perfil(resultSet.getString("foto_perfil"));

        return usuario;
    }
}

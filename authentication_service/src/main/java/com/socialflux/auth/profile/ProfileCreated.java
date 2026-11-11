package com.socialflux.auth.profile;

/** {@code legacyId}: id inteiro do perfil (TEMPORÁRIO, enquanto houver domínios no backend legado). */
public record ProfileCreated(int legacyId, boolean created) {
}

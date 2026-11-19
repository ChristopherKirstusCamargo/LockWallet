# Contribuindo

1. `./gradlew ciCheck` precisa passar.
2. Nunca altere um expected output de fixture para fazer um teste passar: investigue e documente (v2.1 §9).
3. Mudança de dependência: atualize `gradle/libs.versions.toml` e regenere `gradle/verification-metadata.xml` em commit separado (`./gradlew --write-verification-metadata sha256 ciCheck`), revisando o diff.
4. Mudança de formato persistente ou de decisão de segurança exige ADR em `docs/adr/`.
5. Proibido: logs de segredos, telemetria, endpoints mainnet, Float/Double para valores.

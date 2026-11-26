# Contribuindo

O desenvolvimento da Lock Wallet está encerrado na versão final e não há novas versões planejadas. Pull requests de
funcionalidade não serão incorporados.

- **Vulnerabilidades:** relate em privado, conforme [`SECURITY.md`](SECURITY.md).
- **Forks:** permitidos pela licença Apache-2.0. Um fork deve usar outro nome de pacote e outra identidade visual, e
  não é uma versão oficial.

Para quem mantiver um fork, as regras que o código atual segue:

1. `./gradlew ciCheck` precisa passar.
2. Nunca altere o resultado esperado de um vetor de teste para fazer um teste passar; investigue e documente.
3. Mudança de dependência: atualize `gradle/libs.versions.toml` e regenere `gradle/verification-metadata.xml`
   (`./gradlew --write-verification-metadata sha256 ciCheck`), revisando o diff.
4. Mudança de formato persistente ou de decisão de segurança exige um ADR em `docs/adr/`.
5. Proibido: logs de segredos, telemetria, endpoints de rede de teste, `Float`/`Double` para valores.

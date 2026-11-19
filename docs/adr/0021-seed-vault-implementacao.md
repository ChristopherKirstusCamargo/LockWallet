# ADR 0021 — Seed Vault: implementação (Fase G)

- **Status:** Aceito
- **Data:** 2026-11-19
- **Spec:** v2.0 §23.3, ADR 0006/0016, prompt 1.2.

## Decisão
- Módulo `:custody:seedvault` sobre o SDK oficial `com.solanamobile:seedvault-wallet-sdk:0.4.0` (sem dependências transitivas; sha256 em `verification-metadata.xml`).
- Disponibilidade = `SeedVault.isAvailable(ctx, false)` (provedor real do sistema; o simulador **não** conta). Sem Seed Vault ⇒ opção visível porém desabilitada com o motivo; nada é simulado.
- Permissão `com.solanamobile.seedvault.ACCESS_SEED_VAULT` (declarada pelo manifesto do SDK) pedida **só no uso**; o manifesto do SDK também traz `<queries>` para o provedor do sistema.
- Escolha de custódia por carteira: Local (Lock Vault/Connect) ou Seed Vault. Seed Vault: autorizar semente existente, criar nova ou importar — sempre na UI do próprio Seed Vault. A Lock Wallet guarda apenas `authToken`, caminho BIP44 (`m/44'/501'/0'/0'`), endereço público e nome em `noBackupFilesDir/seedvault/seedvault.json` (escrita atômica, formato v1).
- Papéis: um mesmo `authToken` nunca serve dois papéis (Vault × Connect) — `ROLE_CONFLICT`.
- Assinatura: `ChainModel` → `SeedVaultProvider.signTransactionRequest` re-executa o **mesmo analisador** estático e confere o hash da mensagem revisada antes de criar o Intent → Seed Vault autentica e assina → assinatura verificada em Ed25519 contra o endereço da carteira (`BAD_SIGNATURE` caso contrário) → blockhash ainda válido → coordenador (persistir antes de enviar). O PIN da Lock Wallet não substitui a autenticação do Seed Vault (§23.3), por isso não é pedido nesse caminho.
- Sem quiz, sem LWRP, sem “ver frase”, sem “adicionar conta” para carteiras Seed Vault; o detalhe diz que backup/recuperação ficam no Seed Vault. Remover da Lock Wallet desautoriza o token só se nenhuma outra carteira o usa; a frase continua no Seed Vault.
- Token revogado/semente apagada no Seed Vault ⇒ carteira em **quarentena** (não assinável), nunca apagada silenciosamente.
- O app não bloqueia ao ir para segundo plano enquanto a UI do Seed Vault está na frente (`externalPicker`), como no seletor de arquivos.

## Risco residual / limites
- Sem aparelho Solana Mobile disponível para teste: validado com gateway falso (Robolectric) — o fluxo real precisa de teste num Saga/Seeker. Se o Seed Vault assinar bytes diferentes da mensagem (ex.: transação inteira) a verificação Ed25519 recusa e nada é enviado.
- Seed Vault em si é confiado para custódia e autenticação.

## Teste
`SeedVaultProviderTest` (3), `SeedVaultFlowTest` (2).

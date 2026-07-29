# TempoKV — transações, segurança e observabilidade da etapa 7

A etapa 7 completa UC-08, UC-09 e UC-12 sobre o mesmo pipeline usado por RESP
e SQL. Não existe caminho alternativo de escrita: uma mutação fora de
transação e um write set transacional chegam ao `CommitCoordinator`, que
publica WAL e storage nessa ordem.

## Transações MVCC

Cada conexão mantém sua própria `Session`. `BEGIN` registra no
`SnapshotManager` a última versão cujo commit terminou e anexa um
`TransactionContext` à sessão. Leituras atuais usam a versão do snapshot e
depois aplicam as mutações já enfileiradas pela própria sessão, oferecendo
snapshot estável e read-your-writes.

`SET`, `DEL`, `EXPIRE` e `RESTOREAT` apenas acrescentam `Mutation` ao write set
enquanto a transação está ativa. `COMMIT` verifica conflito write-write sob o
mesmo monitor que serializa commits comuns. Se alguma chave possui versão
maior que o snapshot, a transação é abortada antes de alocar versão ou escrever
o WAL. Sem conflito, todas as mutações formam um único `CommitRecord`.
`ROLLBACK` descarta o write set e não aloca versão.

RESP:

```text
BEGIN
SET left L
SET right R
COMMIT
```

SQL:

```sql
BEGIN;
UPSERT INTO tempokv (key, value) VALUES ('left', 'L');
UPSERT INTO tempokv (key, value) VALUES ('right', 'R');
COMMIT;
```

Fechar uma conexão com transação ativa aborta o contexto e libera o snapshot.
O garbage collector usa a versão do snapshot ativo mais antigo como watermark,
preservando a versão necessária até a liberação.

## ACL

`Authenticator.users` resolve identidades com comparação de senha em tempo
constante. `AccessController.rules` associa cada identidade a nomes de comando
e prefixos de chave. Identidades anônimas ou desconhecidas são negadas; comandos
administrativos e transacionais precisam de permissão de comando, mas não
carregam chave.

O servidor empacotado usa a identidade local `default` quando autenticação está
desabilitada. A política do papel `PRIMARY` inclui mutações; a política de
`REPLICA` expõe somente leituras e administração, impedindo escrita local antes
do pipeline de commit. Quando `--authentication-enabled=true`, conexões
anônimas são negadas; integrações que compõem os endpoints diretamente devem
fornecer seu `Authenticator` e suas regras.

## HEALTH, INFO e tracing

`PING`, `HEALTH` e `INFO` existem em RESP e como instruções SQL. `HEALTH`
retorna pares `status`, `code`, `reason` e `updated_at`. `INFO` retorna uma
lista ordenada de pares com papel, versão, conexões, WAL, snapshots, conflitos,
contadores, gauges e latências p50/p95/p99.

O `CommandTracer` registra somente o nome normalizado do comando, sucesso/erro
e duração. Chaves, credenciais e valores nunca são usados em nomes ou valores
de métricas. `INFO` também não consulta o storage por chave e, portanto, não
expõe conteúdo de usuário.

No SQL, respostas administrativas usam duas colunas:

```text
name	value
server.role	PRIMARY
storage.version	7

```

## Verificação

Os testes unitários cobrem snapshot/read-your-writes, rollback, conflito com
barreira determinística, ACL por comando/prefixo, não vazamento no tracing,
percentis e GC com snapshot ativo. Lincheck modela a concorrência do registro
de snapshots. Os testes de integração cobrem UC-08, UC-09 e UC-12 pelos
endpoints públicos.

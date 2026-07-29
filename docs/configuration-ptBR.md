[English](configuration.md) | **Português (Brasil)**

# Referência de configuração do TempoKV

## Fontes de configuração e precedência

O TempoKV resolve a configuração da menor para a maior precedência:

1. defaults internos;
2. um arquivo de propriedades Java em UTF-8;
3. variáveis de ambiente;
4. argumentos de linha de comando.

Selecione o arquivo opcional com `--config=/caminho/tempokv.properties` ou
`TEMPOKV_CONFIG=/caminho/tempokv.properties`. O seletor da CLI prevalece quando
ambos estão presentes. O arquivo deve ser regular, não pode ser link simbólico
e deve ter no máximo 1 MiB. Propriedades desconhecidas, opções CLI
desconhecidas ou duplicadas e valores CLI vazios interrompem a inicialização.
Argumentos CLI usam o formato `--nome=valor`.

As variáveis de autenticação com prefixo `TEMPOKV_SECURITY_` são aliases
suportados. Se as duas formas da mesma variável estiverem definidas, a forma
`TEMPOKV_SECURITY_` prevalece.

## Referência completa de opções

Durações usam a sintaxe ISO-8601, como `PT5S`, `PT15S` e `PT720H`. Limites em
bytes são inteiros decimais. Uma porta com valor `0` solicita uma porta efêmera
quando indicado.

| Finalidade | Opção CLI | Variável de ambiente | Chave de propriedades | Default | Validação/observações |
| --- | --- | --- | --- | --- | --- |
| Endereço de bind para RESP, SQL e replicação do primário | `--bind-address` | `TEMPOKV_BIND_ADDRESS` | `tempokv.bind.address` | `127.0.0.1` | Deve resolver; endereço fora de loopback exige o opt-in de transporte inseguro. |
| Permitir transporte remoto sem criptografia | `--allow-insecure-remote-transport` | `TEMPOKV_ALLOW_INSECURE_REMOTE_TRANSPORT` | `tempokv.transport.allow.insecure.remote` | `false` | Booleano. Também é exigido quando uma réplica se conecta a um host primário fora de loopback. |
| Porta RESP | `--resp-port` | `TEMPOKV_RESP_PORT` | `tempokv.resp.port` | `6379` | `0..65535`; `0` solicita porta efêmera. Não pode coincidir com outra porta explícita do servidor. |
| Porta SQL | `--sql-port` | `TEMPOKV_SQL_PORT` | `tempokv.sql.port` | `6380` | `0..65535`; `0` solicita porta efêmera. Não pode coincidir com outra porta explícita do servidor. |
| Porta de escuta da replicação do primário | `--replication-port` | `TEMPOKV_REPLICATION_PORT` | `tempokv.replication.port` | `6381` | `0..65535`; `0` solicita porta efêmera. Não pode coincidir com outra porta explícita do servidor. |
| Diretório de dados | `--data-dir` | `TEMPOKV_DATA_DIR` | `tempokv.data.dir` | `data` | Normalizado como caminho absoluto; a raiz do sistema de arquivos é rejeitada. Um processo por vez pode bloquear o diretório. |
| Papel do nó | `--node-role` | `TEMPOKV_NODE_ROLE` | `tempokv.node.role` | `PRIMARY` | `PRIMARY` ou `REPLICA`, sem distinção entre maiúsculas e minúsculas. Uma réplica exige replicação. |
| Ativar replicação | `--replication-enabled` | `TEMPOKV_REPLICATION_ENABLED` | `tempokv.replication.enabled` | `false` | Booleano. Exige persistência e token de replicação válido. |
| Identificador do nó | `--node-id` | `TEMPOKV_NODE_ID` | `tempokv.node.id` | `tempokv-node` | Texto UTF-8 não vazio, limitado por `max-username-bytes`. |
| Host primário usado por uma réplica | `--primary-host` | `TEMPOKV_PRIMARY_HOST` | `tempokv.primary.host` | `127.0.0.1` | Não vazio. Host fora de loopback em uma réplica exige o opt-in de transporte inseguro. |
| Porta de replicação do primário usada por uma réplica | `--primary-replication-port` | `TEMPOKV_PRIMARY_REPLICATION_PORT` | `tempokv.primary.replication.port` | `6381` | `1..65535`. |
| Segredo compartilhado de replicação | `--replication-token` | `TEMPOKV_REPLICATION_TOKEN` | `tempokv.replication.token` | nenhum | Exigido somente quando a replicação está ativa. Deve ter 16–4096 bytes UTF-8, respeitar `max-credential-bytes`, conter ao menos três code points distintos e não ser um valor trivial rejeitado. Nunca é registrado em log pelo TempoKV. |
| Idade de retenção histórica | `--history-retention` | `TEMPOKV_HISTORY_RETENTION` | `tempokv.history.retention` | `PT720H` | Duração ISO-8601 positiva. |
| Ativar WAL, snapshots e recovery | `--persistence-enabled` | `TEMPOKV_PERSISTENCE_ENABLED` | `tempokv.persistence.enabled` | `false` | Booleano. Deve ser `true` quando a replicação está ativa. |
| Ativar autenticação de clientes | `--authentication-enabled` | `TEMPOKV_AUTHENTICATION_ENABLED` ou `TEMPOKV_SECURITY_AUTHENTICATION_ENABLED` | `tempokv.security.authentication.enabled` | `true` | Booleano. O modo ativo exige usuário e senha explícitos. O modo desativado rejeita credenciais fornecidas. |
| Usuário de autenticação | `--authentication-username` | `TEMPOKV_AUTHENTICATION_USERNAME` ou `TEMPOKV_SECURITY_AUTHENTICATION_USERNAME` | `tempokv.security.authentication.username` | nenhum | Exigido quando a autenticação está ativa; o tamanho UTF-8 não pode exceder `max-username-bytes`. |
| Senha de autenticação | `--authentication-password` | `TEMPOKV_AUTHENTICATION_PASSWORD` ou `TEMPOKV_SECURITY_AUTHENTICATION_PASSWORD` | `tempokv.security.authentication.password` | nenhum | Exigida quando a autenticação está ativa; o tamanho UTF-8 não pode exceder `max-credential-bytes`. Nunca é registrada em log pelo TempoKV. |
| Máximo de conexões de clientes por protocolo público | `--max-connections-per-protocol` | `TEMPOKV_MAX_CONNECTIONS_PER_PROTOCOL` | `tempokv.limits.connections.per.protocol` | `4096` | `1..100000`, aplicado separadamente a RESP e SQL. |
| Máximo de elementos em array RESP | `--max-resp-array-elements` | `TEMPOKV_MAX_RESP_ARRAY_ELEMENTS` | `tempokv.limits.resp.array.elements` | `1024` | `1..65536`. |
| Máximo de bytes por comando | `--max-command-bytes` | `TEMPOKV_MAX_COMMAND_BYTES` | `tempokv.limits.command.bytes` | `16777216` | `1024..67108864`. Statements SQL têm limite adicional de 1 MiB no handler SQL. |
| Máximo de bytes do usuário | `--max-username-bytes` | `TEMPOKV_MAX_USERNAME_BYTES` | `tempokv.limits.username.bytes` | `128` | `1..4096`; também limita o identificador do nó. |
| Máximo de bytes da credencial | `--max-credential-bytes` | `TEMPOKV_MAX_CREDENTIAL_BYTES` | `tempokv.limits.credential.bytes` | `4096` | `8..1048576`; tokens de replicação também são limitados a 4096 bytes. |
| Máximo de mutações em uma transação | `--max-transaction-mutations` | `TEMPOKV_MAX_TRANSACTION_MUTATIONS` | `tempokv.limits.transaction.mutations` | `4096` | `1..100000`. |
| Máximo de bytes do write set da transação | `--max-transaction-write-bytes` | `TEMPOKV_MAX_TRANSACTION_WRITE_BYTES` | `tempokv.limits.transaction.write.bytes` | `33554432` | `1024..268435456`. |
| Máximo de peers de replicação conectados | `--max-replication-peers` | `TEMPOKV_MAX_REPLICATION_PEERS` | `tempokv.limits.replication.peers` | `64` | `1..1024`. |
| Máximo de commits enfileirados por réplica | `--max-pending-replica-commits` | `TEMPOKV_MAX_PENDING_REPLICA_COMMITS` | `tempokv.limits.replication.pending.commits` | `1024` | `1..100000`; um peer que excede a fila limitada é desconectado. |
| Máximo de bytes de commits enfileirados por réplica | `--max-pending-replica-bytes` | `TEMPOKV_MAX_PENDING_REPLICA_BYTES` | `tempokv.limits.replication.pending.bytes` | `67108864` | `1024..536870912` e ao menos `max-command-bytes`. |
| Máximo de bytes do payload de snapshot | `--max-snapshot-bytes` | `TEMPOKV_MAX_SNAPSHOT_BYTES` | `tempokv.limits.snapshot.bytes` | `67108864` | `1024..134217728`; também limita payloads de snapshot do full sync. |
| Timeout de sincronização da replicação | `--replication-sync-timeout` | `TEMPOKV_REPLICATION_SYNC_TIMEOUT` | `tempokv.timeouts.replication.sync` | `PT15S` | Duração ISO-8601 de `PT0.1S` a `PT10M`. |
| Intervalo de heartbeat da replicação | `--replication-heartbeat-interval` | `TEMPOKV_REPLICATION_HEARTBEAT_INTERVAL` | `tempokv.timeouts.replication.heartbeat.interval` | `PT5S` | Duração ISO-8601 de `PT0.05S` a `PT1M`. |
| Timeout de heartbeat da replicação | `--replication-heartbeat-timeout` | `TEMPOKV_REPLICATION_HEARTBEAT_TIMEOUT` | `tempokv.timeouts.replication.heartbeat` | `PT15S` | Duração ISO-8601 de `PT0.1S` a `PT10M`; deve ser ao menos o dobro do intervalo de heartbeat. |

## Configurações válidas

Os exemplos usam diretórios de dados separados. Remova esses diretórios somente
depois de interromper seus nós.

### Nó local com autenticação explicitamente desabilitada

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/local-open \
  --authentication-enabled=false
```

### Nó local com autenticação habilitada

```bash
: "${TEMPOKV_AUTHENTICATION_USERNAME:?defina um usuário}"
: "${TEMPOKV_AUTHENTICATION_PASSWORD:?defina uma senha}"
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/local-authenticated
```

As duas credenciais são lidas do ambiente. Não as coloque diretamente em um
comando que possa ser preservado no histórico do shell.

### Primário persistente com replicação

```bash
: "${TEMPOKV_REPLICATION_TOKEN:?defina um segredo não trivial com ao menos 16 bytes}"
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/primary \
  --persistence-enabled=true \
  --authentication-enabled=false \
  --replication-enabled=true \
  --node-role=PRIMARY \
  --node-id=primary
```

### Réplica persistente

```bash
: "${TEMPOKV_REPLICATION_TOKEN:?defina o mesmo segredo usado pelo primário}"
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/replica \
  --persistence-enabled=true \
  --authentication-enabled=false \
  --replication-enabled=true \
  --node-role=REPLICA \
  --node-id=replica \
  --primary-host=127.0.0.1 \
  --primary-replication-port=6381 \
  --resp-port=7379 \
  --sql-port=7380 \
  --replication-port=7381
```

### Arquivo de propriedades

```properties
tempokv.bind.address=127.0.0.1
tempokv.data.dir=./data/properties-node
tempokv.persistence.enabled=true
tempokv.security.authentication.enabled=true
tempokv.security.authentication.username=operator
tempokv.security.authentication.password=substitua-por-um-valor-privado
tempokv.history.retention=PT720H
```

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --config=./tempokv.properties
```

Proteja um arquivo de configuração que contenha credenciais com permissões do
sistema de arquivos e não faça commit dele.

### Variáveis de ambiente para Docker

```bash
export TEMPOKV_REPLICATION_TOKEN='<substitua-por-um-segredo-privado-de-16+-bytes>'
docker compose up --build
```

A demonstração Compose desabilita explicitamente a autenticação de clientes,
aceita transporte sem criptografia dentro da rede isolada, persiste dados do
primário e da réplica em volumes separados e publica portas de clientes apenas
no loopback do host.

## Configurações inválidas e falhas de inicialização

- Autenticação ativa sem as duas credenciais falha antes do servidor iniciar.
  Autenticação desabilitada com credenciais fornecidas também falha.
- Replicação ativa sem token explícito válido falha. Tokens vazios, curtos,
  triviais, com pouca variedade ou acima do limite são rejeitados.
- Um nó `REPLICA` com replicação desabilitada é rejeitado.
- Replicação sem persistência é rejeitada.
- Um bind fora de loopback, ou uma réplica usando host primário fora de
  loopback, é rejeitado sem permissão explícita para transporte remoto inseguro.
- As portas de escuta RESP, SQL e replicação devem ser diferentes quando não
  forem zero.
- Limites e timeouts malformados, com overflow, fora do intervalo ou
  incoerentes são rejeitados com o nome da opção relevante.
- A inicialização falha quando outro processo já mantém o lock do diretório de
  dados.

## Fronteira de segurança

O TempoKV não possui TLS nativo e não suporta exposição direta à Internet
pública. Loopback é o default. Transporte remoto sem criptografia exige opt-in
explícito e deve ser limitado a uma rede privada confiável. Para outras redes,
posicione o TempoKV atrás de proxy, túnel ou service mesh que termine TLS.

Não existem credenciais padrão de clientes nem tokens padrão utilizáveis de
replicação. A autenticação é habilitada por default, portanto os valores padrão
falham intencionalmente na inicialização até que credenciais sejam fornecidas
ou a autenticação seja explicitamente desabilitada. Não faça commit de senhas,
tokens de replicação ou arquivos de propriedades que contenham credenciais.

## Docker Compose

A demonstração primário–réplica exige `TEMPOKV_REPLICATION_TOKEN`. Forneça o
mesmo valor aos dois serviços por meio do shell:

```bash
TEMPOKV_REPLICATION_TOKEN='<substitua-por-um-segredo-privado-de-16+-bytes>' \
  docker compose up --build
```

O healthcheck do primário envia um `PING` RESP funcional. O Compose inicia a
réplica somente depois que o primário é considerado saudável.

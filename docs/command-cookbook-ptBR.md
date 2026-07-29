# Livro de receitas de comandos

Os exemplos RESP usam `redis-cli -p 6379`. Os comandos são exibidos como
digitados no prompt. Exemplos SQL podem ser enviados ao endpoint textual na
porta `6380`; cada instrução deve terminar com `;` e cada resposta é uma tabela
separada por tabulações e terminada por uma linha em branco.

Os exemplos presumem um nó no loopback explicitamente sem autenticação ou uma
sessão já autenticada.

## Administração e autenticação

### Autenticar uma sessão RESP

```text
AUTH operator <password>
```

O sucesso retorna `OK`. Credenciais inválidas retornam um erro e não alteram a
identidade da sessão atual. A autenticação deve ser configurada na
inicialização; não há usuário nem senha padrão.

Sessões SQL usam:

```sql
AUTH operator <password>;
```

O resultado possui uma única coluna `status` com o valor `OK`.

### Verificar a conectividade

RESP:

```text
PING
```

Resultado esperado: `PONG`.

SQL:

```sql
PING;
```

Formato esperado:

```text
status
PONG
```

## Operações chave-valor atuais

### Gravar e ler

```text
SET profile Ada
GET profile
```

`SET` retorna `OK`. `GET` retorna a bulk string armazenada ou nil quando nenhum
valor atual está visível. Cada `SET` bem-sucedido fora de uma transação cria
uma nova versão global de commit.

### Excluir

```text
DEL profile
```

Retorna `1` quando um valor visível foi excluído e `0` quando não havia um
valor atual. A exclusão anexa um tombstone; ela não apaga o histórico retido.

### Equivalentes SQL

```sql
UPSERT INTO tempokv (key, value) VALUES ('profile', 'Ada');
SELECT key, value FROM tempokv WHERE key = 'profile';
DELETE FROM tempokv WHERE key = 'profile';
```

`UPSERT` retorna `status=OK`, o `SELECT` pontual retorna zero ou uma linha e
`DELETE` retorna uma contagem `affected`. Somente a tabela lógica `tempokv` é
suportada, e leituras pontuais exigem `WHERE key = '...'`.

## TTL

### Adicionar uma expiração

```text
SET session value
EXPIRE session 60
TTL session
```

`EXPIRE` retorna `1` para uma chave visível existente e `0` caso contrário.
`TTL` retorna os segundos inteiros restantes, `-1` para um valor sem expiração
e `-2` para um valor atual ausente ou expirado.

A expiração cria uma versão com o prazo. Depois, a expiração ativa confirma um
tombstone de expiração pelo caminho normal de commit/WAL. Não há sintaxe SQL
para TTL.

## Leituras históricas

### Ler por versão

```text
GETAT profile VERSION 1
```

Retorna o valor retido mais recente cuja versão global de commit seja menor ou
igual à versão solicitada. Versões globais de uma chave não precisam ser
contíguas.

### Ler por timestamp

```text
GETAT profile TIMESTAMP 2026-01-01T00:00:00Z
```

Retorna o valor retido mais recente confirmado no instante ISO-8601 informado
ou antes dele. Um ponto anterior à criação da chave retorna nil. Um tombstone
retorna erro de exclusão; um ponto removido pela retenção retorna um erro
distinto de histórico indisponível.

Equivalentes SQL:

```sql
SELECT value FROM tempokv AS OF VERSION 1 WHERE key = 'profile';
SELECT value FROM tempokv
AS OF TIMESTAMP '2026-01-01T00:00:00Z'
WHERE key = 'profile';
```

## Histórico

```text
HISTORY profile
HISTORY profile 10 25
```

Os argumentos são `HISTORY key [offset [limit]]`. O limite padrão é 100 e o
máximo é 1000. Os resultados vêm do mais recente para o mais antigo; cada
entrada contém:

1. versão do commit;
2. timestamp do commit em milissegundos desde epoch;
3. o valor ou a simple string `TOMBSTONE`.

Uma chave desconhecida retorna erro. Um offset além da lista retida retorna um
array vazio.

Receitas SQL:

```sql
HISTORY 'profile' LIMIT 25 OFFSET 10;
```

```sql
SELECT version, committed_at, state, value
FROM HISTORY('profile')
WHERE version >= 1
ORDER BY version DESC
LIMIT 25 OFFSET 0;
```

O histórico SQL pode projetar `version`, `committed_at`, `state` e `value`,
filtrar com `version >=`, ordenar por versão e paginar. No máximo 1000 versões
retidas são examinadas por instrução.

## Diff

```text
DIFF profile 1 2
```

O `DIFF` RESP compara coordenadas de versão e retorna cinco campos:

1. estado anterior: `VALUE`, `DELETED` ou `MISSING`;
2. estado posterior;
3. tamanho do prefixo binário comum;
4. sufixo anterior restante ou nil;
5. sufixo posterior restante ou nil.

SQL aceita coordenadas de versão ou timestamp:

```sql
DIFF 'profile' BETWEEN VERSION 1 AND VERSION 2;
```

```sql
DIFF 'profile'
BETWEEN TIMESTAMP '2026-01-01T00:00:00Z'
AND TIMESTAMP '2026-01-02T00:00:00Z';
```

As colunas do resultado SQL são `before_state`, `after_state`,
`common_prefix`, `before_suffix` e `after_suffix`.

## Restauração

```text
RESTOREAT profile 1
```

Fora de uma transação, o resultado é a nova versão de commit. A restauração
copia o valor retido, o tombstone e os metadados de expiração aplicáveis para
um novo commit no head; ela nunca remove as versões criadas depois da origem.

SQL:

```sql
RESTORE 'profile' TO VERSION 1;
```

O resultado contém a nova `version`. A gramática SQL não oferece restauração
por timestamp.

## Transações

```text
BEGIN
SET left L
SET right R
GET left
COMMIT
```

Na receita acima, `BEGIN`, as duas operações `SET` e um `COMMIT` bem-sucedido
retornam `OK`; leituras enxergam um snapshot estável mais as escritas
preparadas pela própria sessão. Outros comandos preparados mantêm seu formato
normal de resposta (`RESTOREAT`, por exemplo, retorna `QUEUED`). Todas as
mutações preparadas são publicadas em uma única versão de commit.

Para descartar o write set:

```text
ROLLBACK
```

Transações concorrentes que escrevem na mesma chave são verificadas no commit.
Um commit conflitante retorna erro e não publica versão nem dados no WAL.
Transações aninhadas e `COMMIT`/`ROLLBACK` sem transação ativa são rejeitados.
Fechar a conexão desfaz a transação ativa.

SQL usa o mesmo gerenciador de transações:

```sql
BEGIN;
UPSERT INTO tempokv (key, value) VALUES ('left', 'L');
UPSERT INTO tempokv (key, value) VALUES ('right', 'R');
SELECT value FROM tempokv WHERE key = 'left';
COMMIT;
```

## Saúde e diagnóstico

```text
HEALTH
INFO
```

`HEALTH` retorna pares nome/valor que incluem `status`, `code`, `reason` e
`updated_at`. `INFO` retorna pares nome/valor ordenados para papel, versão
atual, conexões, persistência, transações, replicação, contadores, gauges e
percentis de latência. Nenhum dos comandos lê chaves ou valores do usuário.

Equivalentes SQL:

```sql
HEALTH;
INFO;
```

Ambos retornam as colunas `name` e `value`.

## Inspeção da replicação

Quando a replicação está configurada, `INFO` inclui:

- `replication.role`;
- `replication.state`;
- `replication.applied_version`;
- `replication.acknowledged_version`;
- `replication.primary_version`;
- `replication.lag`;
- `replication.replicas_connected`.

A configuração da replicação ocorre somente na inicialização. Os endpoints
públicos de réplicas permitem leituras e diagnósticos, mas rejeitam mutações
com erro de somente leitura. TempoKV não implementa promoção automática,
eleição nem failover.

## Limites de protocolo e dados

- Requisições RESP devem ser arrays cujos comandos e argumentos sejam bulk
  strings.
- Chaves são limitadas a 1 MiB e valores a 16 MiB.
- Páginas de histórico são limitadas a 1000 entradas e payloads de resposta
  temporal a 16 MiB.
- Instruções SQL são limitadas a 1 MiB e não aceitam varreduras completas da
  tabela.
- Strings SQL escapam apóstrofos como `''`.
- Células de resultados SQL usam `\N` para null e `base64:` para bytes que não
  sejam UTF-8.

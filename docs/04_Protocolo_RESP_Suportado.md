# TempoKV — Protocolo RESP suportado até a E7

O endpoint RESP escuta todas as interfaces na porta configurada por
`--resp-port` (padrão `6379`). Ele usa RESP2 para os fluxos UC-02, UC-03,
UC-05, UC-06, UC-07, UC-08, UC-09 e UC-12.

## Frames reconhecidos

O decodificador aceita arrays (`*`), bulk strings (`$`), simple strings (`+`),
inteiros (`:`), nulos (`$-1` e `*-1`) e erros (`-`). Ele preserva bytes de um
frame incompleto e pode consumir diversos frames de uma única leitura.

## Comandos disponíveis

`PING` continua disponível e responde `+PONG\r\n`.

`HEALTH` retorna pares com estado, código operacional, motivo e instante da
última transição. `INFO` retorna pares ordenados com papel, versão, conexões,
WAL, snapshots, conflitos, métricas e percentis de latência.

As operações key-value atuais são `GET`, `SET`, `DEL`, `EXPIRE` e `TTL`.

As operações temporais introduzidas na E4 são:

- `GETAT key VERSION version`: retorna o valor da versão mais recente até
  `version`; `GETAT key TIMESTAMP instant`: seleciona o commit mais recente no
  instante ISO-8601 UTC ou antes dele.
- `HISTORY key [offset [limit]]`: retorna versões retidas em ordem decrescente,
  paginadas (o limite padrão é 100 e o máximo é 1000). Cada entrada contém
  versão, instante de commit em milissegundos e valor ou `TOMBSTONE`.
- `DIFF key version-one version-two`: calcula a diferença binária entre os
  pontos. Retorna estado anterior, estado posterior, tamanho do prefixo comum,
  sufixo anterior e sufixo posterior. Os estados são `VALUE`, `DELETED` ou
  `MISSING`; valores inexistentes usam nulo RESP.
- `RESTOREAT key version`: cria uma nova versão a partir do valor (ou tombstone)
  da versão retida e retorna a nova versão. Não remove versões posteriores.

`BEGIN`, `COMMIT` e `ROLLBACK` controlam a transação da conexão. Durante a
transação, leituras atuais usam um snapshot estável e mutações ficam no write
set. `COMMIT` publica todas em uma versão ou retorna conflito antes do WAL;
`ROLLBACK` descarta o conjunto sem criar versão.

Por exemplo, um `GETAT` é codificado como:

```
*4\r\n$5\r\nGETAT\r\n$7\r\nprofile\r\n$7\r\nVERSION\r\n$1\r\n1\r\n
```

A chave ausente retorna nulo RESP. Um tombstone histórico e um ponto removido
por retenção retornam erros distintos; a segunda situação nunca é confundida
com chave inexistente. Os comandos podem ser repetidos na mesma conexão e em
pipeline. Outros formatos de pedido ou comandos retornam uma resposta `-ERR`.
Frames RESP inválidos também retornam `-ERR` com uma mensagem determinística.

Antes de cada operação temporal, o servidor aplica a retenção configurada por
`--history-retention`. A versão atual e versões protegidas pelo low-watermark de
snapshots permanecem disponíveis; pontos coletados retornam erro de histórico
indisponível. As métricas `history.versions_collected` e
`history.last_collection_removed` tornam essa manutenção observável.

Na E7, autenticação e ACL continuam fora dos handlers de negócio.
`AccessController` autoriza por identidade, comando e prefixo de chave; uma
negação retorna `-ERR command is not permitted`.
Endpoints compostos com `Authenticator.users` aceitam
`AUTH username password`; credenciais inválidas retornam um erro sem alterar a
identidade atual da sessão.

Com `--persistence-enabled=true`, mutações confirmadas são sincronizadas no WAL
antes da resposta. Falhas de append ou fsync retornam `-ERR commit could not be
made durable` e não publicam a mutação no estado corrente.

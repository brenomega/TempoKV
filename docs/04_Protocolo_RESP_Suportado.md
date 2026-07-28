# TempoKV — Protocolo RESP suportado na E2

O endpoint RESP é um servidor TCP em `127.0.0.1` na porta configurada por
`--resp-port` (padrão `6379`). A etapa E2 implementa RESP2 para permitir o
fluxo administrativo UC-02.

## Frames reconhecidos

O decodificador aceita arrays (`*`), bulk strings (`$`), simple strings (`+`),
inteiros (`:`), nulos (`$-1` e `*-1`) e erros (`-`). Ele preserva bytes de um
frame incompleto e pode consumir diversos frames de uma única leitura.

## Comando disponível

O único pedido semântico desta etapa é `PING`, codificado como:

```
*1\r\n$4\r\nPING\r\n
```

A resposta é `+PONG\r\n`. O comando pode ser repetido na mesma conexão e em
pipeline. Outros formatos de pedido ou comandos retornam uma resposta `-ERR`.
Frames RESP inválidos também retornam `-ERR` com uma mensagem determinística.

Autenticação e autorização são permissivas nesta etapa; elas já fazem parte do
pipeline para que políticas reais possam ser adicionadas sem acoplar handlers a
sockets.

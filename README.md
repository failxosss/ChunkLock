# ChunkLock

Paper plugin (1.21+, Java 21). Hráč napíše `/chunk` a dostane vlastní chunk v void světě.
Okolní chunky jsou zamčené - u každého je cedulka s itemy (klik = odevzdat) a možností
zaplatit penězi (shift + klik, vyžaduje Vault).

## Build
Potřebuješ Javu 21 a Maven.

    mvn clean package

Výsledek: `target/ChunkLock-1.0.0.jar` -> do `plugins/`. Vault je volitelný (peníze).

## Příkazy
| Příkaz | Popis |
|---|---|
| `/chunk` | teleport na aktivní chunk (vytvoří ho, když ještě nemáš) |
| `/chunk home` | teleport na vlastní chunk |
| `/chunk info`, `/chunk members` | informace, členové |
| `/chunk invite <hráč>` | pozvat hráče (klikací přijmout/odmítnout) |
| `/chunk accept\|deny [jméno]` | reakce na pozvánku |
| `/chunk permission <hráč> [oprávnění] [true/false]` | bez oprávnění otevře menu |
| `/chunk kick <hráč>`, `/chunk leave` | vyhodit člena / odejít |
| `/chunk reset confirm` | smazat vlastní chunk a začít znovu |
| `/chunk admin reload\|tp\|unlock\|delete` | admin (`chunklock.admin`) |

Oprávnění členů: `build, break, interact, containers, animals, unlock, invite`.

## Permissions
`chunklock.use` (default true), `chunklock.admin`, `chunklock.bypass.protection`, `chunklock.bypass.commands` (op).

## Konfigurace
Vše v `config.yml` (stromy a šance, itemy a ceny po tierech, světy, omezení příkazů
podle světa) a `messages.yml` (MiniMessage).

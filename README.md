# ChunkLock

Paper plugin (1.21+, Java 21). Hráč napíše `/chunk` a dostane vlastní chunk v void světě.
Okolní chunky jsou zamčené - u každého je cedulka s itemy (klik = odevzdat) a možností
zaplatit penězi (shift + klik, vyžaduje Vault).

## Build
Potřebuješ Javu 21 a Maven.

    mvn clean package

Výsledek: `target/ChunkLock-1.0.0.jar` -> do `plugins/`. Vault je volitelný (peníze).

Na GitHubu se plugin sestaví sám (záložka **Actions** -> poslední běh -> **Artifacts** -> `ChunkLock`).

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
| `/chunk list` | chunky, kde jsi majitel nebo člen |
| `/chunk switch <majitel>` | přepnout aktivní chunk pro `/chunk` |
| `/chunk invites` | čekající pozvánky (klikací) |
| `/chunk map` | textová mapa odemčených chunků |
| `/chunk top` | žebříček podle odemčených chunků |
| `/chunk contributions` | kolik itemů kdo z týmu odevzdal na odemykání |

> `/chunk admin delete <hráč>` teleportuje majitele (a hráče na ostrově) na `delete.location` z configu a spustí `delete.commands`. Svět pro chunky se nastavuje v `chunk-world.name` (výchozí `chunkbasebase`).
| `/chunk admin reload\|list\|info\|tp\|unlock\|lock\|reset\|delete` | admin (`chunklock.admin`) |
| `/chunk admin addmember\|removemember <hráč> <člen>` | správa členů cizího chunku |
| `/chunk admin mobs <hráč> [on\|off]`, `/chunk admin refresh <hráč>` | spawn mobů / přegenerování cedulek a požadavků |
| `/chunk admin needs <hráč>` | požadavky zamčených chunků vs. kolik jde v sousedním chunku získat |
| `/chunk admin backups [hráč]` | zálohy ostrovů (klikací [obnovit]) |
| `/chunk admin restore <záloha>` | obnoví smazaný ostrov ze zálohy |
| `/chunk admin give <hráč> [počet]` | odemykací svitek na `počet` chunků zdarma |

Oprávnění členů: `build, break, interact, containers, animals, unlock, invite`.

## Permissions
`chunklock.use` (default true), `chunklock.admin`, `chunklock.bypass.protection`, `chunklock.bypass.commands` (op).

## Konfigurace
Vše v `config.yml` (stromy a šance, itemy a ceny po tierech, světy, omezení příkazů
podle světa) a `messages.yml` (MiniMessage).

## PlaceholderAPI (žebříčky pro hologramy)
Vyžaduje PlaceholderAPI (softdepend). Expanze `chunklock` se zaregistruje sama.

| Placeholder | Význam |
|---|---|
| `%chunklock_top_N_name%` / `%chunklock_top_N_value%` | N-tý hráč s nejvíce odemčenými chunky (N = 1-10) / počet chunků |
| `%chunklock_oldest_N_name%` | N-tý hráč s nejstarším chunkem (1 = nejstarší) |
| `%chunklock_oldest_N_value%` / `_date%` / `_days%` | stáří (`12d 4h`) / datum (`03.09.2026`) / dny |
| `%chunklock_unlocked%` / `%chunklock_age%` | počet chunků / stáří chunku hráče |

Žebříčky se přepočítávají každých 10 s. Datum vytvoření se ukládá do `data/islands/<uuid>.yml` (`created`);
u starších ostrovů bez něj se použije první připojení hráče na server.

## Generování chunků z normálního světa

V `config.yml` sekce `generation` (výchozí `mode: WORLD`): plugin si vytvoří vanilla svět `chunklock_source`
(název i seed jdou nastavit) a při vytvoření ostrova a každém odemčení chunku z něj zkopíruje odpovídající chunk
do void světa: bloky, biomy, jeskyně, rudy, struktury, truhly i spawnery. Sousední chunky ostrova pocházejí ze
sousedních chunků zdrojového světa, takže terén a biomy na sebe plynule navazují. Start ostrova se hledá na souši
(ne v oceánu). `mode: FLAT` je nouzový jednoduchý plochý ostrov (tráva, hlína, kámen). Ostrovy vytvořené dřív zůstávají ploché.

## Požadavky na odemčení podle surovin v chunku

V `config.yml` (`unlock.mode: CHUNK`, výchozí) se požadavky nepíšou pevně, ale počítají se z toho, co jde
v sousedním odemčeném chunku opravdu získat. Při vygenerování každého chunku plugin spočítá bloky (rudy, dřevo,
písek, jíl, kámen...) a uloží je k ostrovu. Zamčený chunk pak vyžaduje jen část zásoby z chunku, ze kterého se
rozšiřuje:

- vyžaduje se `share` % zásoby dané suroviny (začíná na `share-start`, s každým odemčeným chunkem roste o
  `share-per-chunk` až do `share-max`) a nikdy víc než `max-share-per-item` %,
- vybírají se jen suroviny, kterých v chunku je aspoň `min-pool` a které už jsou "odemčené" (`from`) - diamanty
  a smaragdy se tedy chtějí až později a jen když v chunku jsou,
- první požadavek je vždy "jádrový" (ruda nebo dřevo), další se losují podle váhy; počet je `min-items`..`max-items`,
- rudy se vyžadují jako ingoty (počítá se s vypálením), `per-block` je minimum z vanilla dropu,
- vypočtené požadavky se k chunku uloží, takže se čísla na cedulce nemění; odevzdané itemy nepropadnou,
- starší ostrovy se při prvním použití naskenují podle aktuálního stavu chunků.

Seznam surovin (`unlock.chunk.resources`) jde upravit nebo rozšířit. `unlock.mode: TIERS` vrací pevné požadavky
z `unlock.tiers`. V režimu CHUNK se z `tiers` používá jen cena v penězích. `/chunk admin reload` a
`/chunk admin refresh <hráč>` požadavky spočítají znovu. `/chunk admin needs <hráč>` ukáže požadavek a vedle něj,
kolik dané věci v sousedním chunku je.

## Obtížnost podle vzdálenosti od startu

`unlock.distance` v `config.yml`: nejbližší zamčené chunky mají základní požadavky, každý další chunk vzdálenosti
přidá `per-chunk` % (itemy i cena v penězích), nejvýš `max-bonus` %. `metric`: `CHEBYSHEV` (prstence) nebo `MANHATTAN`
(kroky od startu). Platí pro nově spočítané požadavky; u už spočítaných pomůže `/chunk admin refresh <hráč>`.
V režimu CHUNK pořád platí strop `max-share-per-item`.

## Týmové odemykání

Postup u zamčeného chunku je společný pro celý ostrov. S `unlock.team.deliver-without-permission: true` může itemy
odevzdat každý člen (oprávnění `unlock` pak chrání jen platbu penězi). Ostatním členům online se napíše, kdo co přidal
a kolik % je hotovo (`unlock.team.notify`), po odemčení se vypíše, kdo se podílel. `/chunk contributions` ukazuje žebříček.

## Slevy při platbě penězi

`unlock.money.discounts`: permise -> sleva v % (platí nejvyšší, kterou hráč má), např. `chunklock.discount.vip: 10`.
Na cedulce je vždy základní cena, sleva se projeví až při placení (SHIFT + klik).

## Zálohy ostrovů

Před `/chunk reset confirm`, `/chunk admin reset` a `/chunk admin delete` se uloží kopie ostrova do
`plugins/ChunkLock/data/backups` (`backup.keep-per-island` určuje, kolik se jich drží). `/chunk admin restore <záloha>`
vrátí postup, členy a odemčené chunky a terén vygeneruje znovu. Stavby hráčů se neobnovují. Hráč nesmí mít jiný ostrov
(po resetu nejdřív `/chunk admin delete <hráč>`).

## Cedulky jezdí za hráčem

U každé zamčené stěny má každý hráč vlastní cedulku, kterou vidí jen on (ostatním je skrytá). Zůstává na stěně, ale svisle se posouvá podle výšky toho hráče, takže ji máš vždy před sebou, i když kopeš dolů nebo stavíš nahoru. Drobné pohyby (skákání) se ignorují. Cedulky vznikají hráči, když stojí na ostrově, a mizí, když odejde. Počet entit tak roste s počtem hráčů na ostrově.

## Odemykací svitek

`/chunk admin give <hráč> 10` dá hráči item (výchozí Nether star, `unlock-token` v configu), který odemkne 10 chunků zdarma.
Hráč s ním v ruce klikne na cedulku zamčeného chunku (pravým i levým), chunk se odemkne a počet nábojů klesne
na 9; při nule svitek zmizí. Počet nábojů je uložený v itemu a vypisuje se v jeho popisu. Svitek smí použít každý
člen ostrova, jen na chunk, který jde odemknout (sousedí s odemčeným).

Svitek jde omezit na majitele a členy s oprávněním `unlock`: v `config.yml` nastav `unlock-token.require-permission: true`
(výchozí `false` = smí ho použít každý člen ostrova).

## Nether a End
Stejný chunk systém funguje i v Netheru a Endu (`dimensions:` v `config.yml`).

- **Nether:** když v chunk světě (nebo ve světě z `dimensions.extra-portal-worlds`) projdeš Nether portálem, dostaneš vlastní chunk v Netheru zkopírovaný ze zdrojového Netheru. Odemyká se věcmi, které v sousedním chunku opravdu najdeš (`unlock.chunk.resources-nether`: netherrack, křemen, zlaté nugety, ancient debris, soul sand, bazalt, glowstone, stonky...). Portál v Netheru vede zpět na ostrov v overworldu.
- **End:** vstup do End portálu (postaveného z rámů a očí) tě přenese na jeden chunk s výstupním portálem a spawne se Ender drak. Drak má boss bar, nerozbíjí bloky a když odletí dál než `dimensions.end.dragon.leash-radius`, vrátí se. Po zabití draka výstupní portál a vejce zůstanou a odemykání dalších chunků (`resources-end`) pokračuje. Portál v Endu vede domů.
- Členové, oprávnění a mobs se sdílejí s hlavním ostrovem v overworldu; reset nebo smazání hlavního ostrova smaže i jeho Nether a End část (se zálohou).
- Výstupní End portál po zabití draka spustí příkazy z `dimensions.end.exit-commands` (výchozí `[player] chunk`), jen v chunk Endu. Prázdný seznam = návrat na ostrov v overworldu.
- `/chunk admin tp <hráč> [overworld|nether|end]` teleportuje do dané dimenze.

**Upozornění:** první start vytvoří dva další zdrojové světy (je to náročné). Let draka s vlastním podiem nebyl na reálném serveru ověřen; kdyby se choval divně, nastav `dimensions.end.dragon.enabled: false` nebo zmenši `leash-radius`. `max-radius` v Endu drž malý, mimo hlavní ostrov je End prázdný.

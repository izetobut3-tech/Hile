# QuickSell Mod (Minecraft 1.21.11 / Fabric)

Tek tuşla çalışan topla → sat döngüsü.

## Nasıl çalışır

**G tuşuna bas** → döngü başlar:
1. Yakında (varsayılan 12 blok) `config/quicksell.json` içindeki eşyalardan biri (prismarine
   parçası, pişmiş tavuk, tavuk tüyü) varsa oyuncuyu ona doğru yürütür.
2. Envanter dolunca (36 slot da doluysa) **otomatik olarak `/sellgui` açar**, eşyaları shift-click
   ile GUI'ye doldurur, menüyü kapatır (sunucun menüden çıkınca satıyor).
3. Satış bitince otomatik olarak tekrar toplamaya döner — döngü sen durdurana kadar sürer.
4. Yakında hiç item kalmazsa ama elinde satılacak bir şey varsa (~5 saniye sonra) yine satışa geçer,
   böylece boşa beklemez.

**G'ye tekrar bas** → döngü durur (o an ne yapıyorsa bitirir, kaldığı yerde durur).
**H'ye bas** → her ne yapıyorsa (yürüme, tıklama, hepsi) **hemen** durur, acil durdurma tuşu.

## Güvenlik kilitleri (otomatik durur)

- Hasar alırsan
- Lav/ateş yakınına gelirsen
- Hedef itemin altı boşluksa (uçurum olabilir) — o itemi atlar
- Bir engelde ~3 saniyeden uzun takılırsan
- `/sellgui` menüsü açılmazsa (komut çalışmadıysa)

**Bu gerçek bir pathfinding değil**, sadece hedefe düz çizgi yürür. Karmaşık arazide (mağara,
çok bloklu engeller, moblar) beklenmedik şekilde takılabilir. Ekrandan tamamen uzaklaşma.

## Ayarlar — `config/quicksell.json`

Mod ilk açılışta bu dosyayı `.minecraft/config/` içine oluşturur:

```json
{
  "sellCommand": "sellgui",
  "guiTitleContains": "Satış Rehberi",
  "openDelayTicks": 4,
  "clickDelayTicks": 2,
  "closeDelayTicks": 6,
  "collectRadius": 12.0,
  "noItemTimeoutTicks": 100,
  "sellItems": [
    "minecraft:prismarine_shard",
    "minecraft:cooked_chicken",
    "minecraft:feather"
  ]
}
```

- `guiTitleContains`: GUI başlığında bu metin geçmezse mod tıklama yapmaz. Sunucunda başlık
  farklıysa değiştir.
- `sellItems`: İstediğin item id'lerini ekle/çıkar.
- Değişiklikten sonra oyunu yeniden başlat.

## Derleme (Build)

Bu ortamda internet erişimi olmadığı için jar'ı senin için derleyemedim, kaynak kod hazır:

1. [Java 21 (JDK)](https://adoptium.net/) kur.
2. Klasörü çıkar, terminalde içine gir.
3. Wrapper dosyaların yoksa yerel Gradle 8.14+ ile `gradle wrapper` çalıştır ya da doğrudan
   kurulu Gradle ile devam et.
4. `./gradlew build` (Linux/Mac) veya `gradlew.bat build` (Windows)
5. Jar `build/libs/quicksell-1.0.0.jar` içinde olacak.
6. **Fabric Loader 0.18.1+** ve **Fabric API 0.141.3+1.21.11** kurulu `.minecraft/mods` klasörüne koy.

## Tuşlar (Kontroller menüsünden değiştirilebilir)

- **G**: Topla+Sat döngüsünü aç/kapat
- **H**: Acil durdur

## Not

Sadece client tarafında tuş/tık/hareket girdilerini otomatikleştirir, sunucuya hile paketi
göndermez. Yine de bazı sunucularda anti-cheat "insan gibi olmayan" hareket paternlerini fark
edip uyarı verebilir — kendi sunucun olduğu için pratikte sorun olmamalı.

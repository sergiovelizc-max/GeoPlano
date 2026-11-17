# GeoPlano – planos PDF georreferenciados en Android y Android Auto

App Android nativa (Kotlin) para ver planos PDF georreferenciados sin conexión, con tu posición GPS sobre el plano. Incluye modo **Android Auto** para ver el plano en la pantalla del auto.

## Funciones

**Teléfono**
- Importar uno o varios PDF (botón +, o "Abrir con / Compartir → GeoPlano" desde Outlook, OneDrive, Drive o WhatsApp).
- Lee la georreferenciación del PDF de forma automática. Funciona con los PDF que exportan ArcGIS Pro, QGIS y GDAL.
- Biblioteca ordenada por cercanía. Muestra "● Estás aquí" en los planos que contienen tu ubicación.
- Visor con pellizco para zoom y doble toque. Al acercarse, la zona visible se vuelve a dibujar en alta resolución, así el plano se mantiene nítido a cualquier zoom.
- Punto GPS con círculo de precisión y cono de dirección de avance. Botón para centrar y seguir tu posición.
- Barra con coordenadas, precisión, altitud y distancia al plano cuando estás fuera de él.
- **Marcadores**: botón en tu ubicación o pulsación larga sobre el plano. Cada uno lleva nombre, nota y color, y se puede editar o borrar tocándolo.
- **Tracks**: grabación en segundo plano (sigue con la pantalla apagada), con notificación y botón "Detener".
- **Medición**: distancia, perímetro y área (m² / ha).
- **Exportar** marcadores y tracks a KML (Google Earth, ArcGIS Pro, QGIS) y CSV por correo, WhatsApp, OneDrive, etc.
- Todo funciona sin conexión y sin Google Play Services.

**Android Auto**
- El plano se dibuja en la pantalla del auto, con tu posición y modo seguir.
- Botones de zoom +/−, centrar, y desplazamiento con la pantalla táctil o el control del auto.
- Recuadro con el nombre del plano y tu estado ("Estás dentro del plano" / "Fuera del plano · 3 km").
- "Planos": lista para elegir el plano, con los que contienen tu ubicación primero.
- "Grabar / Detener" track desde el auto.
- Muestra los marcadores y tracks del plano.

## Compilar e instalar

1. Instala **Android Studio** (versión 2024.3 o más reciente).
2. *File → Open* → elige la carpeta `GeoPlano`. Android Studio descarga Gradle y las dependencias solo; la primera vez tarda unos minutos.
3. Conecta el teléfono con la **depuración USB** activa (*Ajustes → Acerca del teléfono →* toca 7 veces "Número de compilación" → *Opciones de desarrollador → Depuración USB*).
4. Presiona ▶ **Run**. Así queda instalada la app.
5. Para generar un APK y pasarlo a otros: *Build → Build App Bundle(s)/APK(s) → Build APK(s)*.

Pruebas del lector de georreferencia: `./gradlew test` (o clic derecho en `app/src/test` → *Run tests*).

## Probar en Android Auto

Android Auto solo muestra apps de Play Store, salvo que actives el modo desarrollador:

1. En el teléfono abre **Ajustes → Dispositivos conectados → Android Auto** (o la app Android Auto).
2. Baja hasta **Versión** y tócala 10 veces. Acepta el modo desarrollador.
3. Menú ⋮ → **Configuración para desarrolladores** → activa **Fuentes desconocidas**.
4. Conecta el teléfono al auto. GeoPlano aparece en el menú de apps.
5. Abre GeoPlano al menos una vez en el teléfono y concede el permiso de ubicación.

Sin auto, puedes probarlo en el PC con el **Desktop Head Unit (DHU)**: *SDK Manager → SDK Tools → Android Auto Desktop Head Unit Emulator*.

> Para publicar en Play Store, Google revisa manualmente las apps de categoría navegación en Android Auto. Para uso interno (instalación por APK + fuentes desconocidas) no hace falta.

## Cómo exportar el plano desde ArcGIS Pro

*Compartir → Exportar diseño → PDF* y marca **"Exportar información de georreferencia"**. El resto de las opciones pueden quedar como estén. Un plano exportado así aparece en la app con su sistema de coordenadas (ej. `WGS_1984_UTM_Zone_18S`).

## Limitaciones de esta versión

- Usa la primera página del PDF. Si una página tiene un mapa principal y un mapa de ubicación, toma el marco más grande.
- Lee la georreferencia estándar ISO 32000 (la de ArcGIS Pro, QGIS y GDAL). La codificación antigua "GeoPDF" de TerraGo no está soportada.
- No importa GeoTIFF ni KML de entrada (sí exporta KML).
- El plano siempre se muestra con el norte de la página arriba; no rota con la brújula.
- **CarPlay** es solo para iPhone. Esta app cubre Android + Android Auto; un equivalente para iPhone sería otro proyecto (Swift).

## Estructura

```
app/src/main/java/cl/geoplano/
  geo/       Lector de PDF y georreferencia (sin dependencias de Android, probado)
  data/      Biblioteca de planos, marcadores y tracks (JSON local)
  render/    Renderizado del PDF (PdfRenderer), base + detalle por zona
  location/  GPS compartido teléfono/auto (LocationManager)
  track/     Servicio en primer plano para grabar tracks
  export/    KML / CSV
  ui/        Biblioteca, visor y capas
  car/       Android Auto: servicio, pantalla de mapa (superficie) y lista de planos
```

# GeoPlano para iPhone + CarPlay

Mismas funciones que la versión Android: planos PDF georreferenciados sin conexión, posición GPS, mira central con UTM/distancia/rumbo, marcadores, tracks (también con la pantalla apagada), medición, exportar KML/CSV y modo CarPlay.

GitHub compila la app en cada cambio (pestaña **Actions → Compilar iPhone**). Para instalarla en un iPhone hay que pasar por Apple. Los pasos son estos, y **se hacen una sola vez**.

## 1. Cuenta de Apple Developer (99 USD/año)

1. Entra a **developer.apple.com/programs** y elige *Enroll* con tu Apple ID.
   - Como persona: aprobación en horas o pocos días.
   - Como Arauco: requiere el número D-U-N-S de la empresa. Conviene preguntar a TI si Arauco ya tiene una cuenta y pueden agregarte.
2. Cuando esté activa, anota tu **Team ID**: está en *developer.apple.com → Account → Membership details* y tiene 10 caracteres, por ejemplo `AB12CD34EF`.

## 2. Crear la app en App Store Connect

1. **appstoreconnect.apple.com → Apps → + → Nueva app**.
2. Plataforma iOS, nombre `GeoPlano` (si ya está ocupado, usa por ejemplo `GeoPlano Sanidad`), idioma Español.
3. En *ID de paquete* elige o registra **`cl.geoplano.app`**. Si no aparece, créalo en *developer.apple.com → Certificates, IDs & Profiles → Identifiers → +* (App IDs → App).
4. SKU: `geoplano`.

## 3. Clave para que GitHub suba la app

1. **App Store Connect → Usuarios y acceso → Integraciones → App Store Connect API → +**.
2. Nombre `GitHub`, rol **Administrador** (lo necesita para crear los certificados de firma).
3. Descarga el archivo `AuthKey_XXXXXX.p8`. Solo se puede descargar una vez.
4. Anota el **Key ID** y el **Issuer ID** que aparecen en esa página.

## 4. Guardar los datos en GitHub

En el repositorio: **Settings → Secrets and variables → Actions → New repository secret**. Crea estos cinco:

| Nombre | Valor |
|---|---|
| `APPLE_TEAM_ID` | Tu Team ID (paso 1) |
| `ASC_KEY_ID` | Key ID (paso 3) |
| `ASC_ISSUER_ID` | Issuer ID (paso 3) |
| `ASC_KEY_P8` | Abre el `.p8` con el Bloc de notas y pega **todo** el contenido |
| `CARPLAY_APROBADO` | `no` (lo cambias a `si` cuando Apple apruebe CarPlay, paso 6) |

Luego ve a **Actions → Compilar iPhone → Run workflow**. En unos 15 minutos la app aparece en App Store Connect → TestFlight.

## 5. Instalar en el iPhone con TestFlight

1. En App Store Connect → **TestFlight → Pruebas internas → +**, crea un grupo y agrégate con tu correo de Apple ID.
2. En el iPhone instala la app **TestFlight** desde App Store y acepta la invitación que llega por correo.
3. Instala GeoPlano desde TestFlight. Cada nueva versión llega ahí sola.

Las versiones de TestFlight duran 90 días; cada compilación nueva renueva el plazo.

## 6. Permiso de CarPlay

Apple debe autorizar que la app aparezca en CarPlay.

1. Entra a **developer.apple.com/contact/carplay** (con la sesión de tu cuenta de desarrollador).
2. Categoría: **Navigation** (la app dibuja su propio mapa).
3. Descripción sugerida: *"GeoPlano muestra planos PDF georreferenciados de predios forestales, con la posición GPS del vehículo, para orientar a brigadas de sanidad forestal en caminos interiores sin cobertura de datos."*
4. Apple responde por correo, normalmente en días o semanas. Cuando lo aprueben, cambia el secreto `CARPLAY_APROBADO` a `si` y vuelve a ejecutar **Run workflow**.

Hasta entonces la app funciona completa en el iPhone; solo no aparece en la pantalla del auto.

## Limitaciones

Son las mismas de la versión Android: usa la primera página del PDF, solo la georreferencia ISO 32000 (ArcGIS Pro, QGIS, GDAL) y el plano no rota con la brújula.

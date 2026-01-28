// @Keep - No ofuscar ni renombrar esta clase o sus métodos

package mp.kenimon;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.Enumeration;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class Licencias {

    private final ZonePlugin plugin;
    private String licenseKey;
    private final String verificationEndpoint;
    private final String statusEndpoint;
    private boolean valid = false;
    private boolean initialized = false;
    private String licenseInfo = "";
    private Date expirationDate = null;
    private final File licenseFile;
    private FileConfiguration licenseConfig;
    private long lastVerification = 0;
    private final long VERIFICATION_COOLDOWN = TimeUnit.HOURS.toMillis(1); // Verificar cada hora máximo
    private final Map<String, Object> licenseData = new HashMap<>();
    private String serverIP = null;
    private String licenseType = "";
    private final String API_KEY = "1s91s91MKS91ss10sl01sdl1SIDK101s912sw0dks81s1w1w9k9sk1w9kf189wk9d10sd0dl09k109k0sld0190soxkskd1kd";

    // Constructor
    public Licencias(ZonePlugin plugin) {
        this.plugin = plugin;
        this.verificationEndpoint = "http://23.230.3.68:3000/api/verify-license"; // URL actualizada de la API
        this.statusEndpoint = "http://23.230.3.68:3000/api/check-status"; // Nuevo endpoint para comprobaciones de estado
        this.licenseFile = new File(plugin.getDataFolder(), "licencia.yml");
        loadLicenseConfig();
    }

    /**
     * Inicializa el sistema de licencias. Debe llamarse en el onEnable del plugin.
     * @return true si la licencia es válida, false si no lo es
     */
    public boolean initialize() {
        if (initialized) {
            return valid;
        }

        // Crear archivo de licencia si no existe
        if (!licenseFile.exists()) {
            createDefaultLicenseFile();
            plugin.getLogger().severe("=================================================");
            plugin.getLogger().severe("No se ha configurado ninguna licencia para ZonePlugin.");
            plugin.getLogger().severe("Por favor, ingresa tu clave de licencia en el archivo:");
            plugin.getLogger().severe(licenseFile.getAbsolutePath());
            plugin.getLogger().severe("Y reinicia el servidor.");
            plugin.getLogger().severe("=================================================");

            // Notificar a todos los administradores
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.isOp() || player.hasPermission("zoneplugin.admin")) {
                            player.sendMessage(ChatColor.RED + "[ZonePlugin] No hay licencia configurada. Por favor, configura una en licencia.yml y reinicia el servidor.");
                        }
                    }
                }
            }.runTaskLater(plugin, 100L);

            return false;
        }

        // Cargar licencia desde archivo de configuración
        loadLicenseKey();

        // Si la licencia está vacía, mostrar error
        if (licenseKey == null || licenseKey.isEmpty()) {
            plugin.getLogger().severe("=================================================");
            plugin.getLogger().severe("No se ha configurado ninguna licencia para ZonePlugin.");
            plugin.getLogger().severe("Por favor, ingresa tu clave de licencia en el archivo:");
            plugin.getLogger().severe(licenseFile.getAbsolutePath());
            plugin.getLogger().severe("Y reinicia el servidor.");
            plugin.getLogger().severe("=================================================");
            return false;
        }

        // Obtener la IP del servidor
        obtainServerIP();

        // Verificar licencia
        boolean result = verifyLicense();

        // Si es válida, iniciar verificaciones periódicas
        if (result) {
            startPeriodicCheck();
            plugin.getLogger().info("Licencia válida. Tipo: " + licenseType);
        } else {
            plugin.getLogger().severe("=================================================");
            plugin.getLogger().severe("La licencia configurada no es válida.");
            plugin.getLogger().severe("Por favor, verifica la clave de licencia en el archivo:");
            plugin.getLogger().severe(licenseFile.getAbsolutePath());
            plugin.getLogger().severe("Y reinicia el servidor.");
            plugin.getLogger().severe("=================================================");

            // Notificar a todos los administradores
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.isOp() || player.hasPermission("zoneplugin.admin")) {
                            player.sendMessage(ChatColor.RED + "[ZonePlugin] La licencia configurada no es válida.");
                        }
                    }
                }
            }.runTaskLater(plugin, 100L);
        }

        initialized = true;
        return result;
    }

    /**
     * Crea el archivo de licencia predeterminado
     */
    private void createDefaultLicenseFile() {
        try {
            licenseFile.getParentFile().mkdirs();

            // Si el archivo no existe, crear uno con instrucciones simplificadas
            if (!licenseFile.exists()) {
                try (PrintWriter writer = new PrintWriter(licenseFile)) {
                    writer.println("# ZonePlugin - Configuración de licencia");
                    writer.println("# Ingresa tu clave de licencia y reinicia el servidor");
                    writer.println("");
                    writer.println("license:");
                    writer.println("  key: \"\"  # Ingresa tu clave de licencia aquí");
                    writer.println("");
                }
            }

            // Cargar el archivo recién creado
            licenseConfig = YamlConfiguration.loadConfiguration(licenseFile);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo crear el archivo de licencia: " + e.getMessage());
        }
    }

    /**
     * Obtiene la IP pública del servidor
     */
    private void obtainServerIP() {
        try {
            // Intentar obtener la IP automáticamente
            serverIP = getExternalIP();

            // Si no se pudo obtener, intentar con la IP local
            if (serverIP == null || serverIP.isEmpty()) {
                InetAddress localHost = InetAddress.getLocalHost();
                serverIP = localHost.getHostAddress();
            }

            // Si aún no se pudo obtener o es una IP local (127.0.0.1), intentar otra forma
            if (serverIP == null || serverIP.isEmpty() || serverIP.startsWith("127.")) {
                serverIP = getAlternativeIP();
            }

            // Guardamos la IP detectada
            plugin.getLogger().info("IP del servidor detectada: " + serverIP);

        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo determinar la dirección IP del servidor: " + e.getMessage());
            // En caso de error, asignar un valor por defecto
            serverIP = "unknown";
        }
    }

    // Método para obtener IP externa mediante servicios web
    private String getExternalIP() {
        try {
            URL url = new URL("https://checkip.amazonaws.com");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()))) {
                return br.readLine().trim();
            }
        } catch (Exception e) {
            // Intentar con otro servicio si el primero falla
            try {
                URL url = new URL("http://icanhazip.com");
                try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    return br.readLine().trim();
                }
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // Método alternativo para obtener IP del servidor
    private String getAlternativeIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // Filtrar interfaces virtuales y desactivadas
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            return ip;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Carga la clave de licencia del archivo de configuración
     */
    private void loadLicenseKey() {
        licenseKey = licenseConfig.getString("license.key", "");
    }

    /**
     * Carga el archivo de configuración de licencia
     */
    private void loadLicenseConfig() {
        if (!licenseFile.exists()) {
            createDefaultLicenseFile();
        }

        licenseConfig = YamlConfiguration.loadConfiguration(licenseFile);
    }

    /**
     * Guarda la configuración de licencia
     */
    private void saveLicenseConfig() {
        try {
            licenseConfig.save(licenseFile);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo guardar licencia.yml: " + e.getMessage());
        }
    }

    /**
     * Verifica la licencia con el servidor remoto
     */
    public boolean verifyLicense() {
        // Si la licencia está vacía, no es válida
        if (licenseKey == null || licenseKey.isEmpty()) {
            plugin.getLogger().severe("No se ha configurado ninguna licencia.");
            valid = false;
            return false;
        }

        // Si se verificó hace poco, usar resultado en caché
        long now = System.currentTimeMillis();
        if ((now - lastVerification) < VERIFICATION_COOLDOWN && licenseConfig.getBoolean("license.verified", false)) {
            valid = true;
            return true;
        }

        try {
            // Crear conexión HTTP
            URL url = new URL(verificationEndpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-API-Key", API_KEY); // API Key actualizada
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            // Crear JSON con los datos de verificación
            JSONObject requestData = new JSONObject();
            requestData.put("licenseKey", licenseKey);
            requestData.put("serverIp", serverIP);
            requestData.put("pluginName", "ZonePlugin"); // Añadido nombre del plugin
            requestData.put("timestamp", System.currentTimeMillis());

            // Enviar datos
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestData.toJSONString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Obtener respuesta
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Leer respuesta
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    // Parsear JSON de respuesta
                    JSONParser parser = new JSONParser();
                    JSONObject jsonResponse = (JSONObject) parser.parse(response.toString());
                    boolean isValid = (boolean) jsonResponse.get("valid");

                    if (isValid) {
                        // Actualizar timestamp de verificación
                        lastVerification = now;
                        licenseConfig.set("license.verified", true);
                        licenseConfig.set("license.last_verification", lastVerification);

                        // Guardar tipo de licencia
                        licenseType = (String) jsonResponse.get("type");
                        licenseConfig.set("license.type", licenseType);

                        // Guardar expiración si es temporal
                        if (jsonResponse.containsKey("expiresAt") && jsonResponse.get("expiresAt") != null) {
                            String expiresAtStr = (String) jsonResponse.get("expiresAt");
                            try {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                                expirationDate = sdf.parse(expiresAtStr);
                                licenseConfig.set("license.expiration", expiresAtStr);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error al parsear fecha de expiración: " + e.getMessage());
                            }
                        }

                        saveLicenseConfig();
                        valid = true;
                        return true;
                    } else {
                        // Licencia inválida
                        String message = (String) jsonResponse.get("message");
                        plugin.getLogger().severe("Verificación de licencia fallida: " + message);
                        valid = false;
                        return false;
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error al procesar respuesta: " + e.getMessage());
                }
            } else {
                plugin.getLogger().severe("Error en la verificación. Código: " + responseCode);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error de conexión: " + e.getMessage());
        }

        valid = false;
        return false;
    }

    /**
     * Comprueba el estado de la licencia (menos intensivo que la verificación completa)
     */
    private boolean checkLicenseStatus() {
        if (licenseKey == null || licenseKey.isEmpty()) {
            return false;
        }

        try {
            URL url = new URL(statusEndpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-API-Key", API_KEY);
            connection.setDoOutput(true);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            // Crear JSON con datos mínimos para la verificación de estado
            JSONObject requestData = new JSONObject();
            requestData.put("licenseKey", licenseKey);
            requestData.put("serverIp", serverIP);
            requestData.put("pluginName", "ZonePlugin");

            // Enviar datos
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestData.toJSONString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Obtener respuesta
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    JSONParser parser = new JSONParser();
                    JSONObject jsonResponse = (JSONObject) parser.parse(response.toString());

                    // Actualizar última verificación si la licencia es válida
                    boolean isValid = (boolean) jsonResponse.get("valid");
                    if (isValid) {
                        lastVerification = System.currentTimeMillis();
                    }

                    return isValid;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error al verificar estado de licencia: " + e.getMessage());
        }

        return false;
    }

    /**
     * Inicia verificaciones periódicas de la licencia
     */
    private void startPeriodicCheck() {
        // Verificar cada hora
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("Realizando verificación periódica de licencia...");

                // Primero intentar la verificación rápida de estado
                boolean statusOk = checkLicenseStatus();

                // Si la verificación rápida falla, hacer la verificación completa
                if (!statusOk && !verifyLicense()) {
                    plugin.getLogger().severe("¡Verificación periódica fallida! La licencia ya no es válida.");
                    plugin.getLogger().severe("El plugin será deshabilitado en el próximo reinicio del servidor.");

                    // Marcar para desactivar en próximo inicio
                    licenseConfig.set("license.valid", false);
                    saveLicenseConfig();

                    // Notificar a administradores online
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.isOp() || player.hasPermission("zoneplugin.admin")) {
                            player.sendMessage(ChatColor.RED + "[ZonePlugin] ¡Verificación de licencia fallida! El plugin se deshabilitará en el próximo reinicio.");
                        }
                    }
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60 * 30, 20L * 60 * 60); // Verificar cada hora, primera vez a los 30 minutos
    }

    /**
     * Comprueba si la licencia es válida
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Obtiene el tipo de licencia (permanente o temporal)
     */
    public String getLicenseType() {
        return licenseType;
    }

    /**
     * Obtiene la fecha de expiración formateada
     */
    public String getExpirationDate() {
        if (expirationDate == null) {
            return "No disponible";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        return sdf.format(expirationDate);
    }
}

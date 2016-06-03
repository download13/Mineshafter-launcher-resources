package net.minecraft.launcher.game;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.UserType;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;
import com.mojang.launcher.LegacyPropertyMapSerializer;
import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.game.GameInstanceStatus;
import com.mojang.launcher.game.process.GameProcess;
import com.mojang.launcher.game.process.GameProcessBuilder;
import com.mojang.launcher.game.process.GameProcessFactory;
import com.mojang.launcher.game.process.GameProcessRunnable;
import com.mojang.launcher.game.process.direct.DirectGameProcessFactory;
import com.mojang.launcher.game.runner.AbstractGameRunner;
import com.mojang.launcher.updater.DateTypeAdapter;
import com.mojang.launcher.updater.VersionSyncInfo;
import com.mojang.launcher.updater.download.Downloadable;
import com.mojang.launcher.updater.download.assets.AssetIndex;
import com.mojang.launcher.versions.ExtractRules;
import com.mojang.util.UUIDTypeAdapter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.profile.LauncherVisibilityRule;
import net.minecraft.launcher.profile.Profile;
import net.minecraft.launcher.updater.CompleteMinecraftVersion;
import net.minecraft.launcher.updater.Library;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;

public class MinecraftGameRunner extends AbstractGameRunner implements GameProcessRunnable {
	private static final String CRASH_IDENTIFIER_MAGIC = "#@!@#";
	private final Gson gson = new Gson();
	private final DateTypeAdapter dateAdapter = new DateTypeAdapter();
	private final Launcher minecraftLauncher;
	private final String[] additionalLaunchArgs;
	private final GameProcessFactory processFactory = new DirectGameProcessFactory();
	private File nativeDir;
	private LauncherVisibilityRule visibilityRule = LauncherVisibilityRule.CLOSE_LAUNCHER;
	private UserAuthentication auth;
	private Profile selectedProfile;

	public MinecraftGameRunner(net.minecraft.launcher.Launcher minecraftLauncher, String[] additionalLaunchArgs) {
		this.minecraftLauncher = minecraftLauncher;
		this.additionalLaunchArgs = additionalLaunchArgs;
	}

	protected void setStatus(GameInstanceStatus status) {
		synchronized (lock) {
			if ((nativeDir != null) && (status == GameInstanceStatus.IDLE)) {
				LOGGER.info("Deleting " + nativeDir);
				if ((!nativeDir.isDirectory()) || (FileUtils.deleteQuietly(nativeDir))) {
					nativeDir = null;
				} else {
					LOGGER.warn("Couldn't delete " + nativeDir + " - scheduling for deletion upon exit");
					try {
						FileUtils.forceDeleteOnExit(nativeDir);
					} catch (Throwable localThrowable) {}
				}
			}
			super.setStatus(status);
		}
	}

	protected com.mojang.launcher.Launcher getLauncher() {
		return minecraftLauncher.getLauncher();
	}

	protected void downloadRequiredFiles(VersionSyncInfo syncInfo) {
		migrateOldAssets();
		super.downloadRequiredFiles(syncInfo);
	}

	protected void launchGame() throws IOException {
		LOGGER.info("Launching game");
		selectedProfile = minecraftLauncher.getProfileManager().getSelectedProfile();
		auth = minecraftLauncher.getProfileManager().getAuthDatabase().getByUUID(minecraftLauncher.getProfileManager().getSelectedUser());
		if (getVersion() == null) {
			LOGGER.error("Aborting launch; version is null?");
			return;
		}
		nativeDir = new File(getLauncher().getWorkingDirectory(), "versions/" + getVersion().getId() + "/" + getVersion().getId() + "-natives-" + System.nanoTime());
		if (!nativeDir.isDirectory()) {
			nativeDir.mkdirs();
		}
		LOGGER.info("Unpacking natives to " + nativeDir);
		try {
			unpackNatives(nativeDir);
		} catch (IOException e) {
			LOGGER.error("Couldn't unpack natives!", e);
			return;
		}
		File assetsDir;
		try {
			assetsDir = reconstructAssets();
		} catch (IOException e) {
			LOGGER.error("Couldn't unpack natives!", e);
			return;
		}
		File gameDirectory = selectedProfile.getGameDir() == null ? getLauncher().getWorkingDirectory() : selectedProfile.getGameDir();
		LOGGER.info("Launching in " + gameDirectory);
		if (!gameDirectory.exists()) {
			if (!gameDirectory.mkdirs()) {
				LOGGER.error("Aborting launch; couldn't create game directory");
			}
		} else if (!gameDirectory.isDirectory()) {
			LOGGER.error("Aborting launch; game directory is not actually a directory");
			return;
		}

		File serverResourcePacksDir = new File(gameDirectory, "server-resource-packs");
		if (!serverResourcePacksDir.exists()) {
			serverResourcePacksDir.mkdirs();
		}

		GameProcessBuilder processBuilder = new GameProcessBuilder((String) Objects.firstNonNull(selectedProfile.getJavaPath(), OperatingSystem.getCurrentPlatform().getJavaDir()));
		processBuilder.withSysOutFilter(new Predicate<String>() {
			public boolean apply(String input) {
				System.out.println(input);
				return input.contains(CRASH_IDENTIFIER_MAGIC);
			}
		});
		processBuilder.directory(gameDirectory);
		processBuilder.withLogProcessor(minecraftLauncher.getUserInterface().showGameOutputTab(this));

		OperatingSystem os = OperatingSystem.getCurrentPlatform();
		if (os.equals(OperatingSystem.OSX)) {
			processBuilder.withArguments(new String[] { "-Xdock:icon=" + getAssetObject("icons/minecraft.icns").getAbsolutePath(), "-Xdock:name=Minecraft" });
		} else if (os.equals(OperatingSystem.WINDOWS)) {
			processBuilder.withArguments(new String[] { "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump" });
		}
		String profileArgs = selectedProfile.getJavaArgs();
		if (profileArgs != null) {
			processBuilder.withArguments(profileArgs.split(" "));
		} else {
			boolean is32Bit = "32".equals(System.getProperty("sun.arch.data.model"));
			String defaultArgument = is32Bit ? "-Xmx512M" : "-Xmx1G";
			defaultArgument += " -Xmn128M";
			processBuilder.withArguments(defaultArgument.split(" "));
		}
		if (minecraftLauncher.usesWinTenHack()) {
			processBuilder.withArguments(new String[] { "-Dos.name=Windows 10" });
			processBuilder.withArguments(new String[] { "-Dos.version=10.0" });
		}
		processBuilder.withArguments("-Djava.library.path=" + this.nativeDir.getAbsolutePath());
		processBuilder.withArguments("-cp", constructClassPath(getVersion()));
		processBuilder.withArguments("info.mineshafter.GameStarter");
		processBuilder.withArguments(getVersion().getMainClass());

		LOGGER.info("Half command: " + StringUtils.join(processBuilder.getFullCommands(), " "));

		String[] args = getMinecraftArguments(getVersion(), selectedProfile, gameDirectory, assetsDir, auth);
		if (args == null) { return; }
		processBuilder.withArguments(args);

		Proxy proxy = getLauncher().getProxy();
		PasswordAuthentication proxyAuth = getLauncher().getProxyAuth();
		if (!proxy.equals(Proxy.NO_PROXY)) {
			InetSocketAddress address = (InetSocketAddress) proxy.address();
			processBuilder.withArguments(new String[] { "--proxyHost", address.getHostName() });
			processBuilder.withArguments(new String[] { "--proxyPort", Integer.toString(address.getPort()) });
			if (proxyAuth != null) {
				processBuilder.withArguments(new String[] { "--proxyUser", proxyAuth.getUserName() });
				processBuilder.withArguments(new String[] { "--proxyPass", new String(proxyAuth.getPassword()) });
			}
		}
		processBuilder.withArguments(additionalLaunchArgs);
		if ((auth == null) || (auth.getSelectedProfile() == null)) {
			processBuilder.withArguments(new String[] { "--demo" });
		}
		if (selectedProfile.getResolution() != null) {
			processBuilder.withArguments(new String[] { "--width", String.valueOf(selectedProfile.getResolution().getWidth()) });
			processBuilder.withArguments(new String[] { "--height", String.valueOf(selectedProfile.getResolution().getHeight()) });
		}
		try {
			LOGGER.debug("Running " + StringUtils.join(processBuilder.getFullCommands(), " "));
			GameProcess process = processFactory.startGame(processBuilder);
			process.setExitRunnable(this);

			setStatus(GameInstanceStatus.PLAYING);
			if (visibilityRule != LauncherVisibilityRule.DO_NOTHING) {
				minecraftLauncher.getUserInterface().setVisible(false);
			}
		} catch (IOException e) {
			LOGGER.error("Couldn't launch game", e);
			setStatus(GameInstanceStatus.IDLE);
			return;
		}
		minecraftLauncher.performCleanups();
	}

	protected CompleteMinecraftVersion getVersion() {
		return (CompleteMinecraftVersion) version;
	}

	private File getAssetObject(String name) throws IOException {
		File assetsDir = new File(getLauncher().getWorkingDirectory(), "assets");
		File indexDir = new File(assetsDir, "indexes");
		File objectsDir = new File(assetsDir, "objects");
		String assetVersion = getVersion().getAssetIndex().getId();
		File indexFile = new File(indexDir, assetVersion + ".json");
		AssetIndex index = (AssetIndex) gson.fromJson(FileUtils.readFileToString(indexFile, Charsets.UTF_8), AssetIndex.class);

		String hash = ((AssetIndex.AssetObject) index.getFileMap().get(name)).getHash();
		return new File(objectsDir, hash.substring(0, 2) + "/" + hash);
	}

	private File reconstructAssets() throws IOException {
		File assetsDir = new File(getLauncher().getWorkingDirectory(), "assets");
		File indexDir = new File(assetsDir, "indexes");
		File objectDir = new File(assetsDir, "objects");
		String assetVersion = getVersion().getAssetIndex().getId();
		File indexFile = new File(indexDir, assetVersion + ".json");
		File virtualRoot = new File(new File(assetsDir, "virtual"), assetVersion);
		if (!indexFile.isFile()) {
			LOGGER.warn("No assets index file " + virtualRoot + "; can't reconstruct assets");
			return virtualRoot;
		}
		AssetIndex index = (AssetIndex) gson.fromJson(FileUtils.readFileToString(indexFile, Charsets.UTF_8), AssetIndex.class);
		if (index.isVirtual()) {
			LOGGER.info("Reconstructing virtual assets folder at " + virtualRoot);
			for (Map.Entry<String, AssetIndex.AssetObject> entry : index.getFileMap().entrySet()) {
				File target = new File(virtualRoot, (String) entry.getKey());
				File original = new File(new File(objectDir, ((AssetIndex.AssetObject) entry.getValue()).getHash().substring(0, 2)), ((AssetIndex.AssetObject) entry.getValue()).getHash());
				if (!target.isFile()) {
					FileUtils.copyFile(original, target, false);
				}
			}
			FileUtils.writeStringToFile(new File(virtualRoot, ".lastused"), dateAdapter.serializeToString(new Date()));
		}
		return virtualRoot;
	}

	private String[] getMinecraftArguments(CompleteMinecraftVersion version, Profile selectedProfile, File gameDirectory, File assetsDirectory, UserAuthentication authentication) {
		if (version.getMinecraftArguments() == null) {
			LOGGER.error("Can't run version, missing minecraftArguments");
			setStatus(GameInstanceStatus.IDLE);
			return null;
		}
		Map<String, String> map = new HashMap<String, String>();
		StrSubstitutor substitutor = new StrSubstitutor(map);
		String[] split = version.getMinecraftArguments().split(" ");

		map.put("auth_access_token", authentication.getAuthenticatedToken());
		map.put("user_properties", new GsonBuilder().registerTypeAdapter(PropertyMap.class, new LegacyPropertyMapSerializer()).create().toJson(authentication.getUserProperties()));
		map.put("user_property_map", new GsonBuilder().registerTypeAdapter(PropertyMap.class, new PropertyMap.Serializer()).create().toJson(authentication.getUserProperties()));
		if ((authentication.isLoggedIn()) && (authentication.canPlayOnline())) {
			if ((authentication instanceof YggdrasilUserAuthentication)) {
				map.put("auth_session", String.format("token:%s:%s", new Object[] { authentication.getAuthenticatedToken(), UUIDTypeAdapter.fromUUID(authentication.getSelectedProfile().getId()) }));
			} else {
				map.put("auth_session", authentication.getAuthenticatedToken());
			}
		} else {
			map.put("auth_session", "-");
		}
		if (authentication.getSelectedProfile() != null) {
			map.put("auth_player_name", authentication.getSelectedProfile().getName());
			map.put("auth_uuid", UUIDTypeAdapter.fromUUID(authentication.getSelectedProfile().getId()));
			map.put("user_type", authentication.getUserType().getName());
		} else {
			map.put("auth_player_name", "Player");
			map.put("auth_uuid", new UUID(0L, 0L).toString());
			map.put("user_type", UserType.LEGACY.getName());
		}
		map.put("profile_name", selectedProfile.getName());
		map.put("version_name", version.getId());

		map.put("game_directory", gameDirectory.getAbsolutePath());
		map.put("game_assets", assetsDirectory.getAbsolutePath());

		map.put("assets_root", new File(getLauncher().getWorkingDirectory(), "assets").getAbsolutePath());
		map.put("assets_index_name", getVersion().getAssetIndex().getId());
		map.put("version_type", getVersion().getType().getName());
		for (int i = 0; i < split.length; i++) {
			split[i] = substitutor.replace(split[i]);
		}
		return split;
	}

	private void migrateOldAssets() {
		File sourceDir = new File(getLauncher().getWorkingDirectory(), "assets");
		File objectsDir = new File(sourceDir, "objects");
		if (!sourceDir.isDirectory()) { return; }
		IOFileFilter migratableFilter = FileFilterUtils.notFileFilter(FileFilterUtils.or(new IOFileFilter[] { FileFilterUtils.nameFileFilter("indexes"), FileFilterUtils.nameFileFilter("objects"),
				FileFilterUtils.nameFileFilter("virtual"), FileFilterUtils.nameFileFilter("skins") }));
		for (File file : new TreeSet<File>(FileUtils.listFiles(sourceDir, TrueFileFilter.TRUE, migratableFilter))) {
			String hash = Downloadable.getDigest(file, "SHA-1", 40);
			File destinationFile = new File(objectsDir, hash.substring(0, 2) + "/" + hash);
			if (!destinationFile.exists()) {
				LOGGER.info("Migrated old asset {} into {}", new Object[] { file, destinationFile });
				try {
					FileUtils.copyFile(file, destinationFile);
				} catch (IOException e) {
					LOGGER.error("Couldn't migrate old asset", e);
				}
			}
			FileUtils.deleteQuietly(file);
		}
		File[] assets = sourceDir.listFiles();
		if (assets != null) {
			for (File file : assets) {
				if ((!file.getName().equals("indexes")) && (!file.getName().equals("objects")) && (!file.getName().equals("virtual")) && (!file.getName().equals("skins"))) {
					LOGGER.info("Cleaning up old assets directory {} after migration", new Object[] { file });
					FileUtils.deleteQuietly(file);
				}
			}
		}
	}

	private void unpackNatives(File targetDir) throws IOException {
		OperatingSystem os = OperatingSystem.getCurrentPlatform();
		Collection<Library> libraries = getVersion().getRelevantLibraries();
		for (Library library : libraries) {
			Map<OperatingSystem, String> nativesPerOs = library.getNatives();
			if ((nativesPerOs != null) && (nativesPerOs.get(os) != null)) {
				File file = new File(getLauncher().getWorkingDirectory(), "libraries/" + library.getArtifactPath((String) nativesPerOs.get(os)));
				ZipFile zip = new ZipFile(file);
				ExtractRules extractRules = library.getExtractRules();
				try {
					Enumeration<? extends ZipEntry> entries = zip.entries();
					while (entries.hasMoreElements()) {
						ZipEntry entry = (ZipEntry) entries.nextElement();
						if ((extractRules == null) || (extractRules.shouldExtract(entry.getName()))) {
							File targetFile = new File(targetDir, entry.getName());
							if (targetFile.getParentFile() != null) {
								targetFile.getParentFile().mkdirs();
							}
							if (!entry.isDirectory()) {
								BufferedInputStream inputStream = new BufferedInputStream(zip.getInputStream(entry));

								byte[] buffer = new byte[2048];
								FileOutputStream outputStream = new FileOutputStream(targetFile);
								BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
								try {
									int length;
									while ((length = inputStream.read(buffer, 0, buffer.length)) != -1) {
										bufferedOutputStream.write(buffer, 0, length);
									}
								} finally {
									Downloadable.closeSilently(bufferedOutputStream);
									Downloadable.closeSilently(outputStream);
									Downloadable.closeSilently(inputStream);
								}
							}
						}
					}
				} finally {
					zip.close();
				}
			}
		}
	}

	private String constructClassPath(CompleteMinecraftVersion version) {
		StringBuilder result = new StringBuilder();
		Collection<File> classPath = version.getClassPath(OperatingSystem.getCurrentPlatform(), getLauncher().getWorkingDirectory());
		String separator = System.getProperty("path.separator");
		for (File file : classPath) {
			if (!file.isFile()) { throw new RuntimeException("Classpath file not found: " + file); }
			if (result.length() > 0) {
				result.append(separator);
			}
			result.append(file.getAbsolutePath());
		}

		result.append(separator);
		result.append("ms-starter.jar");
		
		result.append(separator);
		result.append(".");

		return result.toString();
	}

	public void onGameProcessEnded(GameProcess process) {
		int exitCode = process.getExitCode();
		if (exitCode == 0) {
			LOGGER.info("Game ended with no troubles detected (exit code " + exitCode + ")");
			if (visibilityRule == LauncherVisibilityRule.CLOSE_LAUNCHER) {
				LOGGER.info("Following visibility rule and exiting launcher as the game has ended");
				getLauncher().shutdownLauncher();
			} else if (visibilityRule == LauncherVisibilityRule.HIDE_LAUNCHER) {
				LOGGER.info("Following visibility rule and showing launcher as the game has ended");
				minecraftLauncher.getUserInterface().setVisible(true);
			}
		} else {
			LOGGER.error("Game ended with bad state (exit code " + exitCode + ")");
			LOGGER.info("Ignoring visibility rule and showing launcher due to a game crash");
			minecraftLauncher.getUserInterface().setVisible(true);

			String errorText = null;
			Collection<String> sysOutLines = process.getSysOutLines();
			String[] sysOut = (String[]) sysOutLines.toArray(new String[sysOutLines.size()]);
			for (int i = sysOut.length - 1; i >= 0; i--) {
				String line = sysOut[i];
				int pos = line.lastIndexOf("#@!@#");
				if ((pos >= 0) && (pos < line.length() - "#@!@#".length() - 1)) {
					errorText = line.substring(pos + "#@!@#".length()).trim();
					break;
				}
			}
			if (errorText != null) {
				File file = new File(errorText);
				if (file.isFile()) {
					LOGGER.info("Crash report detected, opening: " + errorText);
					InputStream inputStream = null;
					try {
						inputStream = new FileInputStream(file);
						BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
						StringBuilder result = new StringBuilder();
						String line;
						while ((line = reader.readLine()) != null) {
							if (result.length() > 0) {
								result.append("\n");
							}
							result.append(line);
						}
						reader.close();

						minecraftLauncher.getUserInterface().showCrashReport(getVersion(), file, result.toString());
					} catch (IOException e) {
						LOGGER.error("Couldn't open crash report", e);
					} finally {
						Downloadable.closeSilently(inputStream);
					}
				} else {
					LOGGER.error("Crash report detected, but unknown format: " + errorText);
				}
			}
		}
		setStatus(GameInstanceStatus.IDLE);
	}

	public void setVisibility(LauncherVisibilityRule visibility) {
		visibilityRule = visibility;
	}

	public UserAuthentication getAuth() {
		return auth;
	}

	public Profile getSelectedProfile() {
		return selectedProfile;
	}
}

/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.loader.thin;

import java.io.File;
import java.net.URL;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.loader.ExecutableArchiveLauncher;
import org.springframework.boot.loader.LaunchedURLClassLoader;
import org.springframework.boot.loader.archive.Archive;
import org.springframework.boot.loader.archive.Archive.Entry;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

import ch.qos.logback.classic.Level;

/**
 *
 * @author Dave Syer
 */
public class ThinJarLauncher extends ExecutableArchiveLauncher {

	private static final Logger log = LoggerFactory.getLogger(ThinJarLauncher.class);

	/**
	 * System property key for main class to launch. Defaults to finding it via
	 * Start-Class of the main archive.
	 */
	public static final String THIN_MAIN = "thin.main";

	/**
	 * System property to signal a "dry run" where dependencies are resolved but the main
	 * method is not executed.
	 */
	public static final String THIN_DRYRUN = "thin.dryrun";

	/**
	 * System property to signal a "classpath run" where dependencies are resolved but the
	 * main method is not executed and the output is in the form of a classpath.
	 */
	public static final String THIN_CLASSPATH = "thin.classpath";

	/**
	 * System property holding the path to the root directory, where Maven repository and
	 * settings live. Defaults to <code>${user.home}/.m2</code>.
	 */
	public static final String THIN_ROOT = "thin.root";

	/**
	 * System property used by wrapper to communicate the location of the main archive.
	 * Can also be used in a dryrun or classpath launch to override the archive location
	 * with a file or "maven://..." style URL.
	 */
	public static final String THIN_ARCHIVE = "thin.archive";

	/**
	 * A parent archive (URL or "maven://..." locator) that controls the classpath and
	 * dependency management defaults for the main archive.
	 */
	public static final String THIN_PARENT = "thin.parent";

	/**
	 * The path to thin properties files (as per thin.name), as a comma-separated list of
	 * resources (these locations plus relative /META-INF will be searched). Defaults to
	 * current directory and classpath:/.
	 */
	public static final String THIN_LOCATION = "thin.location";

	/**
	 * The name of the launchable (i.e. the properties file name). Defaults to "thin" (so
	 * "thin.properties" is the default file name with no profiles).
	 */
	public static final String THIN_NAME = "thin.name";

	/**
	 * The name of the profile to run, changing the location of the properties files to
	 * look up.
	 */
	public static final String THIN_PROFILE = "thin.profile";

	/**
	 * Flag to say that classloader should be parent first (default true). You may need it
	 * to be false if the target archive contains classes in the root, and you want to
	 * also use a Java agent (because the agent and the app classes have to be all on the
	 * classpath). Some agents work with the default settings though.
	 */
	public static final String THIN_PARENT_FIRST = "thin.parent.first";

	/**
	 * Flag to say that classloader parent should be the boot loader, not the system class
	 * loader. Default true;
	 */
	public static final String THIN_PARENT_BOOT = "thin.parent.boot";

	private StandardEnvironment environment = new StandardEnvironment();
	private boolean debug;

	public static void main(String[] args) throws Exception {
		LogUtils.setLogLevel(Level.OFF);
		new ThinJarLauncher(args).launch(args);
	}

	protected ThinJarLauncher(String[] args) throws Exception {
		super(computeArchive(args));
	}

	@Override
	protected void launch(String[] args) throws Exception {
		addCommandLineProperties(args);
		args = removeThinArgs(args);
		String root = environment.resolvePlaceholders("${" + THIN_ROOT + ":}");
		boolean classpath = !"false".equals(
				environment.resolvePlaceholders("${" + THIN_CLASSPATH + ":false}"));
		boolean trace = !"false"
				.equals(environment.resolvePlaceholders("${trace:false}"));
		if (classpath) {
			this.debug = false;
			LogUtils.setLogLevel(Level.OFF);
		}
		else {
			this.debug = trace
					|| !"false".equals(environment.resolvePlaceholders("${debug:false}"));
		}
		if (debug || trace) {
			if (trace) {
				LogUtils.setLogLevel(Level.TRACE);
			}
			else {
				LogUtils.setLogLevel(Level.INFO);
			}
		}
		if (classpath) {
			List<Archive> archives = getClassPathArchives();
			System.out.println(classpath(archives));
			return;
		}
		if (!"false".equals(
				environment.resolvePlaceholders("${" + THIN_DRYRUN + ":false}"))) {
			getClassPathArchives();
			log.info("Downloaded dependencies"
					+ (!StringUtils.hasText(root) ? "" : " to " + root));
			return;
		}
		super.launch(args);
	}

	private String[] removeThinArgs(String[] args) {
		List<String> result = new ArrayList<>();
		boolean escaped = false;
		for (String arg : args) {
			if ("--".equals(arg)) {
				escaped = true;
				continue;
			}
			if (!escaped && arg.startsWith("--thin.")) {
				continue;
			}
			result.add(arg);
		}
		return result.toArray(new String[0]);
	}

	private String classpath(List<Archive> archives) throws Exception {
		StringBuilder builder = new StringBuilder();
		String separator = System.getProperty("path.separator");
		boolean first = true;
		for (Archive archive : archives) {
			if (!first) {
				first = false;
				continue;
			}
			if (builder.length() > 0) {
				builder.append(separator);
			}
			log.info("Archive: {}", archive);
			String uri = archive.getUrl().toURI().toString();
			if (uri.startsWith("jar:")) {
				uri = uri.substring("jar:".length());
			}
			if (uri.startsWith("file:")) {
				uri = uri.substring("file:".length());
			}
			if (uri.endsWith("!/")) {
				uri = uri.substring(0, uri.length()-"!/".length());
			}
			builder.append(new File(uri).getCanonicalPath());
		}
		return builder.toString();
	}

	private void addCommandLineProperties(String[] args) {
		if (args == null || args.length == 0) {
			return;
		}
		MutablePropertySources properties = environment.getPropertySources();
		SimpleCommandLinePropertySource source = new SimpleCommandLinePropertySource(
				"commandArgs", args);
		if (!properties.contains("commandArgs")) {
			properties.addFirst(source);
		}
		else {
			properties.replace("commandArgs", source);
		}
	}

	@Override
	protected ClassLoader createClassLoader(URL[] urls) throws Exception {
		// Use the system classloader (the one that the JVM started with), not the one
		// from this class:
		ClassLoader parent = ClassLoader.getSystemClassLoader();
		if ("true".equals(
				environment.resolvePlaceholders("${" + THIN_PARENT_BOOT + ":true}"))) {
			parent = parent.getParent();
		}
		ThinJarClassLoader loader = new ThinJarClassLoader(
				ArchiveUtils.addNestedClasses(getArchive(), urls, "BOOT-INF/classes/"),
				parent);
		if ("true".equals(
				environment.resolvePlaceholders("${" + THIN_PARENT_FIRST + ":true}"))) {
			// Use a (traditional) parent first class loader
			loader.setParentFirst(true);
		}
		else {
			loader.setParentFirst(false);
		}
		return loader;
	}

	@Override
	protected String getMainClass() throws Exception {
		String mainClass = environment.resolvePlaceholders("${" + THIN_MAIN + ":}");
		if (StringUtils.hasText(mainClass)) {
			return mainClass;
		}
		return ArchiveUtils.findMainClass(getArchive());
	}

	@Override
	protected List<Archive> getClassPathArchives() throws Exception {
		String parent = environment
				.resolvePlaceholders("${" + ThinJarLauncher.THIN_PARENT + ":}");
		String name = environment
				.resolvePlaceholders("${" + ThinJarLauncher.THIN_NAME + ":thin}");
		String locations = environment
				.resolvePlaceholders("${" + ThinJarLauncher.THIN_LOCATION + ":}");
		String[] profiles = environment
				.resolvePlaceholders("${" + ThinJarLauncher.THIN_PROFILE + ":}")
				.split(",");
		String root = environment.resolvePlaceholders("${" + THIN_ROOT + ":}");
		Archive parentArchive = null;
		if (StringUtils.hasText(parent)) {
			parentArchive = ArchiveUtils.getArchive(parent);
		}
		PathResolver resolver = new PathResolver(DependencyResolver.instance());
		if (StringUtils.hasText(locations)) {
			resolver.setLocations(locations.split(","));
		}
		if (StringUtils.hasText(root)) {
			resolver.setRoot(root);
		}
		resolver.setOverrides(getSystemProperties());
		List<Archive> archives = resolver.resolve(parentArchive, getArchive(), name,
				profiles);
		return archives;
	}

	private Properties getSystemProperties() {
		Properties properties = new Properties();
		try {
			Properties system = System.getProperties();
			for (Object key : system.keySet()) {
				String name = key.toString();
				if (name.startsWith("thin.properties.")) {
					name = name.substring("thin.properties.".length());
					properties.setProperty(name, system.getProperty(key.toString()));
				}
			}
		}
		catch (AccessControlException e) {
			// ignore
		}
		if (environment.getPropertySources().contains("commandArgs")) {
			SimpleCommandLinePropertySource commandArgs = (SimpleCommandLinePropertySource) environment
					.getPropertySources().get("commandArgs");
			for (String key : commandArgs.getPropertyNames()) {
				String name = key.toString();
				if (name.startsWith("thin.properties.")) {
					name = name.substring("thin.properties.".length());
					properties.setProperty(name, commandArgs.getProperty(key.toString()));
				}
			}
		}
		return properties;
	}

	@Override
	protected boolean isNestedArchive(Entry entry) {
		return false;
	}

	private static Archive computeArchive(String[] args) throws Exception {
		String path = getProperty(THIN_ARCHIVE);
		for (String arg : args) {
			String prefix = "--" + THIN_ARCHIVE;
			if (arg.startsWith(prefix)) {
				// You can always override --thin.archive on the command line
				if (arg.length() <= prefix.length() + 1) {
					// ... even cancel it by setting it to empty
					path = null;
				}
				else {
					path = arg.substring(prefix.length() + 1);
				}
			}
		}
		return ArchiveUtils.getArchive(path);
	}

	static String getProperty(String key) {
		if (System.getProperty(key) != null) {
			return System.getProperty(key);
		}
		return System.getenv(key.replace(".", "_").toUpperCase());
	}

	private static class ThinJarClassLoader extends LaunchedURLClassLoader {

		private boolean parentFirst = false;

		public ThinJarClassLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		public void setParentFirst(boolean parentFirst) {
			this.parentFirst = parentFirst;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve)
				throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				// First, check if the class has already been loaded
				Class<?> c = findLoadedClass(name);
				if (c == null) {
					try {
						if (!parentFirst) {
							return findClass(name);
						}
					}
					catch (ClassNotFoundException e) {
					}
					return super.loadClass(name, resolve);
				}
				return c;
			}
		}

		@Override
		public URL getResource(String name) {

			URL url = null;

			if (parentFirst) {
				url = getParent().getResource(name);
				if (url != null) {
					return (url);
				}
			}

			url = findResource(name);
			if (url != null) {
				return (url);
			}

			if (!parentFirst) {
				url = getParent().getResource(name);
				if (url != null) {
					return (url);
				}
			}

			return (null);

		}

	}

}

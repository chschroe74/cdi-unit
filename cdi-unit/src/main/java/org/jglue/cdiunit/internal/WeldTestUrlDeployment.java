/*
 *    Copyright 2011 Bryn Cooke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jglue.cdiunit.internal;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.decorator.Decorator;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.Stereotype;
import javax.enterprise.inject.spi.Extension;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.interceptor.Interceptor;

import com.google.common.base.Predicate;
import org.jboss.weld.bootstrap.api.Bootstrap;
import org.jboss.weld.bootstrap.api.ServiceRegistry;
import org.jboss.weld.bootstrap.api.helpers.SimpleServiceRegistry;
import org.jboss.weld.bootstrap.spi.BeanDeploymentArchive;
import org.jboss.weld.bootstrap.spi.BeanDiscoveryMode;
import org.jboss.weld.bootstrap.spi.BeansXml;
import org.jboss.weld.bootstrap.spi.Deployment;
import org.jboss.weld.bootstrap.spi.Metadata;
import org.jboss.weld.bootstrap.spi.Scanning;
import org.jboss.weld.environment.se.WeldSEBeanRegistrant;
import org.jboss.weld.metadata.BeansXmlImpl;
import org.jboss.weld.resources.spi.ResourceLoader;
import org.jglue.cdiunit.ActivatedAlternatives;
import org.jglue.cdiunit.AdditionalClasses;
import org.jglue.cdiunit.AdditionalClasspaths;
import org.jglue.cdiunit.AdditionalPackages;
import org.jglue.cdiunit.CdiRunner;
import org.jglue.cdiunit.ProducesAlternative;
import org.jglue.cdiunit.internal.easymock.EasyMockExtension;
import org.jglue.cdiunit.internal.jsf.ViewScopeExtension;
import org.jglue.cdiunit.internal.mockito.MockitoExtension;
import org.jglue.cdiunit.internal.servlet.MockHttpServletRequestImpl;
import org.jglue.cdiunit.internal.servlet.MockHttpServletResponseImpl;
import org.jglue.cdiunit.internal.servlet.MockHttpSessionImpl;
import org.jglue.cdiunit.internal.servlet.MockServletContextImpl;
import org.jglue.cdiunit.internal.servlet.ServletObjectsProducer;
import org.mockito.Mock;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WeldTestUrlDeployment implements Deployment {
	private final BeanDeploymentArchive beanDeploymentArchive;
	private Collection<Metadata<Extension>> extensions = new ArrayList<Metadata<Extension>>();
	private static final Logger log = LoggerFactory.getLogger(WeldTestUrlDeployment.class);
	private Set<URL> cdiClasspathEntries = new HashSet<URL>();
	private final ServiceRegistry serviceRegistry = new SimpleServiceRegistry();

	public WeldTestUrlDeployment(ResourceLoader resourceLoader, Bootstrap bootstrap, TestConfiguration testConfiguration) throws IOException {

		populateCdiClasspathSet();
		BeansXml beansXml = createBeansXml();

		Set<String> discoveredClasses = new LinkedHashSet<String>();
		Set<String> alternatives = new HashSet<String>();
		discoveredClasses.add(testConfiguration.getTestClass().getName());
		Set<Class<?>> classesToProcess = new LinkedHashSet<Class<?>>();
		Set<Class<?>> classesProcessed = new HashSet<Class<?>>();
		Set<Class<?>> classesToIgnore = findMockedClassesOfTest(testConfiguration.getTestClass());

		classesToProcess.add(testConfiguration.getTestClass());
		extensions.add(createMetadata(new TestScopeExtension(testConfiguration.getTestClass()), TestScopeExtension.class.getName()));
		if (testConfiguration.getTestMethod() != null) {
			extensions.add(createMetadata(new ProducerConfigExtension(testConfiguration.getTestMethod()), ProducerConfigExtension.class.getName()));
		}

		try {
			Class.forName("javax.faces.view.ViewScoped");
			extensions.add(createMetadata(new ViewScopeExtension(), ViewScopeExtension.class.getName()));
		} catch (ClassNotFoundException e) {

		}

		try {
			Class.forName("javax.servlet.http.HttpServletRequest");
			classesToProcess.add(InRequestInterceptor.class);
			classesToProcess.add(InSessionInterceptor.class);
			classesToProcess.add(InConversationInterceptor.class);
			classesToProcess.add(CdiUnitInitialListenerProducer.class);
			classesToProcess.add(MockServletContextImpl.class);
			classesToProcess.add(MockHttpSessionImpl.class);
			classesToProcess.add(MockHttpServletRequestImpl.class);
			classesToProcess.add(MockHttpServletResponseImpl.class);

			// If this is an old version of weld then add the producers
			try {
				Class.forName("org.jboss.weld.bean.AbstractSyntheticBean");
			} catch (ClassNotFoundException e) {
				classesToProcess.add(ServletObjectsProducer.class);
			}

		} catch (ClassNotFoundException e) {
		}

		classesToProcess.addAll(testConfiguration.getAdditionalClasses());

		while (!classesToProcess.isEmpty()) {

			Class<?> c = classesToProcess.iterator().next();

			if ((isCdiClass(c) || Extension.class.isAssignableFrom(c)) && !classesProcessed.contains(c) && !c.isPrimitive()
					&& !classesToIgnore.contains(c)) {
				classesProcessed.add(c);
				if (!c.isAnnotation()) {
					discoveredClasses.add(c.getName());
				}
				if (Extension.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())) {
					try {
						extensions.add(createMetadata((Extension) c.newInstance(), c.getName()));
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				if (c.isAnnotationPresent(Interceptor.class)) {
					beansXml.getEnabledInterceptors().add(createMetadata(c.getName(), c.getName()));
				}

				if (c.isAnnotationPresent(Decorator.class)) {
					beansXml.getEnabledDecorators().add(createMetadata(c.getName(), c.getName()));
				}

				if (isAlternativeStereotype(c)) {
					beansXml.getEnabledAlternativeStereotypes().add(createMetadata(c.getName(), c.getName()));

				}

				AdditionalClasses additionalClasses = c.getAnnotation(AdditionalClasses.class);
				if (additionalClasses != null) {
					for (Class<?> supportClass : additionalClasses.value()) {
						classesToProcess.add(supportClass);
					}
					for (String lateBound : additionalClasses.late()) {
						try {
							Class<?> clazz = Class.forName(lateBound);
							classesToProcess.add(clazz);
						} catch (ClassNotFoundException e) {
							throw new RuntimeException(e);
						}

					}
				}

				AdditionalClasspaths additionalClasspaths = c.getAnnotation(AdditionalClasspaths.class);
				if (additionalClasspaths != null) {
					for (Class<?> additionalClasspath : additionalClasspaths.value()) {

						Reflections reflections = new Reflections(new ConfigurationBuilder().setScanners(new TypesScanner())
								.setUrls(
										new File(additionalClasspath.getProtectionDomain().getCodeSource().getLocation()
												.getPath()).toURI().toURL()));

						classesToProcess.addAll(ReflectionUtils.forNames(
								reflections.getStore().get(TypesScanner.class.getSimpleName()).keySet(),
								new ClassLoader[] { getClass().getClassLoader() }));
					}
				}

				AdditionalPackages additionalPackages = c.getAnnotation(AdditionalPackages.class);
				if (additionalPackages != null) {
					for (Class<?> additionalPackage : additionalPackages.value()) {
						final String packageName = additionalPackage.getPackage().getName();
						Reflections reflections = new Reflections(new ConfigurationBuilder()
								.setScanners(new TypesScanner())
								.setUrls(additionalPackage.getProtectionDomain().getCodeSource().getLocation()
												).filterInputsBy(new Predicate<String>() {

									@Override
									public boolean apply(String input) {
										return input.startsWith(packageName)
												&& !input.substring(packageName.length() + 1, input.length() - 6).contains(".");

									}
								}));
						classesToProcess.addAll(ReflectionUtils.forNames(
								reflections.getStore().get(TypesScanner.class.getSimpleName()).keySet(),
								new ClassLoader[] { getClass().getClassLoader() }));

					}
				}

				ActivatedAlternatives alternativeClasses = c.getAnnotation(ActivatedAlternatives.class);
				if (alternativeClasses != null) {
					for (Class<?> alternativeClass : alternativeClasses.value()) {
						classesToProcess.add(alternativeClass);

						if (!isAlternativeStereotype(alternativeClass)) {
							alternatives.add(alternativeClass.getName());
						}
					}
				}

				for (Annotation a : c.getAnnotations()) {

					if (!a.annotationType().getPackage().getName().equals("org.jglue.cdiunit")) {
						classesToProcess.add(a.annotationType());
					}
				}

				Type superClass = c.getGenericSuperclass();
				if (superClass != null && superClass != Object.class) {
					addClassesToProcess(classesToProcess, superClass);
				}

				for (Field field : c.getDeclaredFields()) {
					if (field.isAnnotationPresent(Inject.class) || field.isAnnotationPresent(Produces.class)) {
						addClassesToProcess(classesToProcess, field.getGenericType());
					}
					if (field.getType().equals(Provider.class) || field.getType().equals(Instance.class)) {
						addClassesToProcess(classesToProcess, field.getGenericType());
					}
				}
				for (Method method : c.getDeclaredMethods()) {
					if (method.isAnnotationPresent(Inject.class) || method.isAnnotationPresent(Produces.class)) {
						for (Type param : method.getGenericParameterTypes()) {
							addClassesToProcess(classesToProcess, param);
						}
						addClassesToProcess(classesToProcess, method.getGenericReturnType());

					}
				}
			}

			classesToProcess.remove(c);
		}

		beansXml.getEnabledAlternativeStereotypes().add(
				createMetadata(ProducesAlternative.class.getName(), ProducesAlternative.class.getName()));

		for (String alternative : alternatives) {
			beansXml.getEnabledAlternativeClasses().add(createMetadata(alternative, alternative));
		}

		try {
			Class.forName("org.mockito.Mock");
			extensions.add(createMetadata(new MockitoExtension(), MockitoExtension.class.getName()));
		} catch (ClassNotFoundException e) {

		}

		try {
			Class.forName("org.easymock.EasyMockRunner");
			extensions.add(createMetadata(new EasyMockExtension(), EasyMockExtension.class.getName()));
		} catch (ClassNotFoundException e) {

		}

		extensions.add(createMetadata(new WeldSEBeanRegistrant(), WeldSEBeanRegistrant.class.getName()));

		beanDeploymentArchive = new BeanDeploymentArchiveImpl("cdi-unit" + UUID.randomUUID(), discoveredClasses, beansXml);
		beanDeploymentArchive.getServices().add(ResourceLoader.class, resourceLoader);
		log.debug("CDI-Unit discovered:");
		for (String clazz : discoveredClasses) {
			if (!clazz.startsWith("org.jglue.cdiunit.internal.")) {
				log.debug(clazz);
			}
		}

	}

	private static <T> Metadata<T> createMetadata(T value, String location) {
		try {
			return new org.jboss.weld.bootstrap.spi.helpers.MetadataImpl<>(value, location);
		} catch (NoClassDefFoundError e) {
			// MetadataImpl moved to a new package in Weld 2.4, old copy removed in 3.0
			try {
				// If Weld < 2.4, the new package isn't there, so we try the old package.
				//noinspection unchecked
				Class<Metadata<T>> oldClass = (Class<Metadata<T>>) Class.forName("org.jboss.weld.metadata.MetadataImpl");
				Constructor<Metadata<T>> ctor = oldClass.getConstructor(Object.class, String.class);
				return ctor.newInstance(value, location);
			} catch (ReflectiveOperationException e1) {
				throw new RuntimeException(e1);
			}
		}
	}

	private static Object annotatedDiscoveryMode() {
		try {
			return BeanDiscoveryMode.ANNOTATED;
		} catch (NoClassDefFoundError e) {
			// No such enum in Weld 1.x, but the constructor for BeansXmlImpl has fewer parameters so we don't need it
			return null;
		}
	}

	private static BeansXml createBeansXml() {
		try {
			// The constructor for BeansXmlImpl has added more parameters in newer Weld versions. The parameter list
			// is truncated in older version of Weld where the number of parameters is shorter, thus omitting the
			// newer parameters.
			Object[] initArgs = new Object[] {
					new ArrayList<Metadata<String>>(), new ArrayList<Metadata<String>>(),
					new ArrayList<Metadata<String>>(), new ArrayList<Metadata<String>>(), Scanning.EMPTY_SCANNING,
					// These were added in Weld 2.0:
					new URL("file:cdi-unit"), annotatedDiscoveryMode(), "cdi-unit",
					// isTrimmed: added in Weld 2.4.2 [WELD-2314]:
					false
			};
			Constructor<?> beansXmlConstructor = BeansXmlImpl.class.getConstructors()[0];
			return (BeansXml) beansXmlConstructor.newInstance(
					Arrays.copyOfRange(initArgs, 0, beansXmlConstructor.getParameterCount()));
		} catch (MalformedURLException | ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private void addClassesToProcess(Collection<Class<?>> classesToProcess, Type type) {

		if (type instanceof Class) {
			classesToProcess.add((Class<?>)type);
		}
		if(type instanceof ParameterizedType) {
			ParameterizedType ptype = (ParameterizedType)type;
			classesToProcess.add((Class<?>)ptype.getRawType());
			for(Type arg : ptype.getActualTypeArguments()) {
				addClassesToProcess(classesToProcess, arg);
			}
		}
	}

	private Set<Class<?>> findMockedClassesOfTest(Class<?> testClass) {
		Set<Class<?>> mockedClasses = new HashSet<Class<?>>();

		try {

			for (Field field : testClass.getDeclaredFields()) {
				if (field.isAnnotationPresent(Mock.class)) {
					Class<?> type = field.getType();
					mockedClasses.add(type);
				}
			}
		} catch (NoClassDefFoundError e) {

		}

		try {

			for (Field field : testClass.getDeclaredFields()) {
				if (field.isAnnotationPresent(org.easymock.Mock.class)) {
					Class<?> type = field.getType();
					mockedClasses.add(type);
				}
			}
		} catch (NoClassDefFoundError e) {

		}
		return mockedClasses;
	}

	private Object getFieldVal(Object object, String fieldName) {
		try {
			Field field = object.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.get(object);
		} catch (IllegalAccessException | NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	private void populateCdiClasspathSet() throws IOException {
		ClassLoader classLoader = WeldTestUrlDeployment.class.getClassLoader();
		List<URL> entries;
		if (classLoader instanceof URLClassLoader) {
			entries = new ArrayList<>(Arrays.asList(((URLClassLoader) classLoader).getURLs()));
		} else {
			// h/t to lukehutch (fast-classpath-scanner)
			// This should work for jdk.internal.loader.BuiltinClassLoader and
			// jdk.internal.loader.ClassLoaders.AppClassLoader.
			// This reflection requires JVM option --add-opens=java.base/jdk.internal.loader=ALL-UNNAMED
			// and may not work at all after Java 9.
			Object urlClassPath = getFieldVal(classLoader, "ucp");
			if (urlClassPath == null) {
				throw new RuntimeException("Unknown ClassLoader: "+classLoader);
			}
			//noinspection unchecked
			entries = (List<URL>) getFieldVal(urlClassPath, "path");
		}

		// If this is surefire we need to get the original claspath
		JarInputStream firstEntry = new JarInputStream(entries.get(0).openStream());
		Manifest manifest = firstEntry.getManifest();
		if (manifest != null) {
			String classpath = (String) manifest.getMainAttributes().get(Attributes.Name.CLASS_PATH);
			if (classpath != null) {
				String[] manifestEntries = classpath.split(" ?file:");
				for (String entry : manifestEntries) {
					if (entry.length() > 0) {
						entries.add(new URL("file:" + entry));
					}
				}
			}

		}
		firstEntry.close();

		for (URL url : entries) {
			URLClassLoader cl = new URLClassLoader(new URL[] { url }, null);
			try {

				if (url.getFile().endsWith("/classes/")) {
					URL webInfBeans = new URL(url, "../../src/main/webapp/WEB-INF/beans.xml");
					try {
						webInfBeans.openConnection().connect();;
						cdiClasspathEntries.add(url);
					} catch (IOException e) {

					}
				}
				URL resource = cl.getResource("META-INF/beans.xml");
				boolean cdiUnit = url.equals(CdiRunner.class.getProtectionDomain().getCodeSource().getLocation());
				if (cdiUnit || resource != null || isDirectoryOnClasspath(url)) {
					cdiClasspathEntries.add(url);
				}

			} finally {
				try {
					Method method = cl.getClass().getMethod("close");
					method.invoke(cl);
				} catch (NoSuchMethodException e) {
					//Ignore, we might be running on Java 6
				} catch (IllegalAccessException e) {
					//Ignore, we might be running on Java 6
				} catch (InvocationTargetException e) {
					//Ignore, we might be running on Java 6
				}
			}
		}
		log.debug("CDI classpath entries discovered:");
		for (URL url : cdiClasspathEntries) {
			log.debug("{}", url);
		}

	}

	private boolean isDirectoryOnClasspath(URL classpathEntry) {
		try {
			return new File(classpathEntry.toURI()).isDirectory();
		} catch (IllegalArgumentException e) {
			// Ignore, thrown by File constructor for unsupported URIs
		} catch (URISyntaxException e) {
			// Ignore, does not denote an URI that points to a directory
		}
		return false;
	}

	private boolean isCdiClass(Class<?> c) {
		if (c.getProtectionDomain().getCodeSource() == null) {
			return false;
		}
		URL location = c.getProtectionDomain().getCodeSource().getLocation();
		boolean isCdi = cdiClasspathEntries.contains(location);
		return isCdi;

	}

	private boolean isAlternativeStereotype(Class<?> c) {
		return c.isAnnotationPresent(Stereotype.class) && c.isAnnotationPresent(Alternative.class);
	}

	@Override
	public Iterable<Metadata<Extension>> getExtensions() {
		return extensions;
	}

	public List<BeanDeploymentArchive> getBeanDeploymentArchives() {
		return Collections.singletonList(beanDeploymentArchive);
	}

	public BeanDeploymentArchive loadBeanDeploymentArchive(Class<?> beanClass) {
		return beanDeploymentArchive;
	}

	public BeanDeploymentArchive getBeanDeploymentArchive(Class<?> beanClass) {
		return beanDeploymentArchive;
	}

	@Override
	public ServiceRegistry getServices() {
		return serviceRegistry;
	}
}

package com.efluid.tcbc;

import static com.efluid.tcbc.ScanneClasspath.Exclusion.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.*;

import org.junit.*;
import org.slf4j.*;
import org.yaml.snakeyaml.Yaml;

/**
 * Canevas permettant de parcourir le classpath classe par classe <br>
 * Gestion des exclusions / inclusion par fichier de configuration (fichier properties) <br>
 * Journal des informations et du temps d'exécution<br>
 * <br>
 * Pour définir un autre classpath que celui par défaut, utiliser la variable d'environnement -Dclasspath=XXX<br>
 */
public abstract class ScanneClasspath {

  private static final Logger LOG = LoggerFactory.getLogger(ScanneClasspath.class);

  private static final String ENV_CLASSEPATH = "classpath";
  private String classpath = System.getProperty(ENV_CLASSEPATH);

  public enum Exclusion {
    CLASSE,
    ERREUR
  }

  /**
   * Curseurs du jar et classe lus en cours
   */
  protected Classe classeEnCours;
  protected InputStream fluxEnCours;
  protected Jar jarEnCours;

  /**
   * Filtre indiquant les jars contrôlés
   */
  protected List<String> jarsInclus = new ArrayList<String>();
  private List<String> filtreClassesExclues = new ArrayList<String>();
  private List<String> filtreErreursExclues = new ArrayList<String>();

  /**
   * Utilisés pour effectuer le bilan global
   */
  protected Set<Jar> jarsTraites = new HashSet<Jar>();

  protected Map<Exclusion, Set<String>> exclusions = new HashMap<Exclusion, Set<String>>();

  public ScanneClasspath() {
    exclusions.put(ERREUR, new HashSet<String>());
    exclusions.put(CLASSE, new HashSet<String>());
  }

  protected void addToExclusions(Exclusion typeExclusion, String exclusion) {
    exclusions.get(typeExclusion).add(exclusion);
  }

  /**
   * @return nom du fichier de propriété permettant de configurer le scan
   */
  protected abstract String getFichierConfiguration();

  /**
   * traitement à définir pour chaque classe scannée
   */
  protected abstract void traitementClasseEnCours();

  /**
   * Affiche l'analyse et les erreurs rencontrées
   *
   * @return nombre d'erreur
   */
  protected int logBilan() {
    logExclusion();
    return 0;
  }

  private void logExclusion() {
    doLogList(exclusions.get(CLASSE), "Exclusions des classes");
    doLogList(exclusions.get(ERREUR), "Exclusions des erreurs");
  }

  @Before
  public void init() {
    chargerConfiguration();
  }

  @Test
  public void execute() {
    execute(classpath != null ? new String[] { classpath } : ((String[]) null));
  }

  /**
   * Lance l'exécution du contrôle du byteCode
   */
  private void execute(String... classpath) {
    scannerClasspaths(classpath);
    int erreurs = logBilan();
    terminate();
    isValid(erreurs);
  }

  /**
   * Aucune erreur ne doit être remontée
   */
  protected void isValid(int erreurs) {
    assertThat(0).isEqualTo(erreurs);
  }

  protected void terminate() {
    /* Permet d'effectuer un traitement à la fin du scan */
  }

  /**
   * Charge la configuration de la classe de test, en l'occurrence : Liste des classes à ne pas contrôler
   */
  protected void chargerConfiguration() {
    try {
      InputStream is = TestControleByteCode.class.getClassLoader().getResourceAsStream(getFichierConfiguration());
      if (is == null) {
        LOG.error("Fichier de configuration inexistant : " + getFichierConfiguration());
        return;
      }
      Map<String, ArrayList<String>> configuration = (Map<String, ArrayList<String>>) new Yaml().load(is);
      if (configuration != null) {
        chargerListeConfiguration(configuration, jarsInclus, "jarsInclus");
        chargerListeConfiguration(configuration, filtreClassesExclues, "filtreClassesExclues");
        chargerListeConfiguration(configuration, filtreErreursExclues, "filtreErreursExclues");
      }
    } catch (Throwable ex) {
      LOG.error("Erreur lors de la récupération du fichier de configuration " + getFichierConfiguration());
      LOG.error("", ex);
    }
    LOG.info("Jars inclus : " + jarsInclus);
    LOG.info("Exclusion des classes a ne pas traiter : " + filtreClassesExclues);
    LOG.info("Erreurs a ne pas traiter: " + filtreErreursExclues);
  }

  /**
   * Ajoute les éléments chargés dans le fichier de configuration pour les placer dans la liste correspondante (gère la nullité)
   */
  private static void chargerListeConfiguration(Map<String, ArrayList<String>> configuration, Collection<String> liste, String nomListe) {
    Collection<String> listeAAjouter = configuration.get(nomListe);
    if (listeAAjouter != null) {
      liste.addAll(listeAAjouter);
    }
  }

  /**
   * Parcourt toutes les jars du classpath. Celui passé en paramètre, sinon celui de la JVM en cours
   */
  protected void scannerClasspaths(String... classpaths) {
    String[] chemins = classpaths;
    // Si aucun classpath spécifié on récupère celui du projet en cours
    if (chemins == null || chemins.length == 0) {
      chemins = System.getProperty("java.class.path").split(File.pathSeparator);
    }

    for (String path : chemins) {
      if (path.endsWith(".jar") && isJarInclu(path)) {
        initJarEnCours(path);
        controlerJar();
      } else if (path.endsWith("classes") && isScanneRepertoireClasses()) {
        initJarEnCours(path);
        scannerRepertoireClasses(path);
      }
    }
  }

  private void initJarEnCours(String path) {
    jarEnCours = new Jar(path);
    jarsTraites.add(jarEnCours);
  }

  protected boolean isScanneRepertoireClasses() {
    return false;
  }

  /**
   * Si l'on souhaite récupérer le flux du fichier courant
   */
  protected boolean isAvecFlux() {
    return false;
  }

  /**
   * Scanne le répertoire classes défini dans le classpath
   */
  protected void scannerRepertoireClasses(String chemin) {
    try {
      for (Path classe : getFichiersClass(chemin)) {
        String nomClasseEnCours = classe.toString().substring(chemin.length() + 1);
        nomClasseEnCours = removeEnd(nomClasseEnCours.replaceAll(File.separator.equals("/") ? File.separator : "\\\\", "."), ".class");
        classeEnCours = new Classe(jarEnCours, nomClasseEnCours);
        if (isExclu(CLASSE, classeEnCours.getNom())) {
          continue;
        }

        fluxEnCours = isAvecFlux() ? new FileInputStream(classe.toFile()) : null;
        traitementClasseEnCours();
      }
    } catch (Throwable ex) {
      LOG.error("", ex);
    }
  }

  /**
   * Retourne une liste de tous les fichiers ".class" du répertoire repertoireClasses
   */
  public List<Path> getFichiersClass(String repertoireClasses) throws IOException {
    final List<Path> fichiersClass = new ArrayList<>();
    Files.walkFileTree(Paths.get(repertoireClasses), new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
        if (file.getFileName().toString().endsWith(".class")) {
          fichiersClass.add(file);
        }
        return FileVisitResult.CONTINUE;
      }
    });

    return fichiersClass;
  }

  /**
   * Parcours toutes les classes du jar en cours
   */
  private void controlerJar() {
    LOG.info("Controle JAR : " + jarEnCours);
    try (JarFile jar = new JarFile(jarEnCours.getNom())) {
      Enumeration<JarEntry> enumeration = jar.entries();
      JarEntry jarEntry = null;
      // Boucle sur tous les fichiers contenus dans le JAR
      while (enumeration.hasMoreElements()) {
        jarEntry = enumeration.nextElement();
        // Récupération du nom de chaque fichier
        if (jarEntry.getName().endsWith(".class")) {
          classeEnCours = new Classe(jarEnCours, removeEnd(jarEntry.getName().replaceAll("/", "."), ".class"));
          if (isExclu(CLASSE, classeEnCours.getNom())) {
            continue;
          }
          fluxEnCours = isAvecFlux() ? jar.getInputStream(jarEntry) : null;
          traitementClasseEnCours();
        }
      }
    } catch (Throwable ex) {
      LOG.error("", ex);
    }
  }

  /**
   * Filtre permettant de ne parcourir que les jars souhaités
   */
  protected boolean isJarInclu(String pathJar) {
    return jarsInclus.stream().anyMatch(jar -> pathJar.contains(File.separatorChar + jar));
  }

  /**
   * Indique si l'erreur ou la classe est exclue
   */
  protected boolean isExclu(Exclusion typeExclusion, final String str) {
    for (String exclusion : (CLASSE.equals(typeExclusion) ? filtreClassesExclues : filtreErreursExclues)) {
      if (str.toLowerCase().indexOf(exclusion.toLowerCase()) != -1) {
        addToExclusions(typeExclusion, str);
        return true;
      }
    }
    return false;
  }

  protected static void doLogList(Collection<String> col, String msgEntete) {
    if (col != null && !col.isEmpty()) {
      List<String> liste = (List<String>) ((col instanceof List) ? col : new ArrayList<String>(col));
      LOG.info("|==== "+ msgEntete + " ====|");
      liste.stream().sorted().forEach(s -> LOG.info("\t" + s));
    }
  }

  public static String removeEnd(String str, String remove) {
    if (isNullOrEmpty(str) || isNullOrEmpty(remove) || !str.endsWith(remove)) {
      return str;
    }
    return str.substring(0, str.length() - remove.length());
  }

  public static boolean isNullOrEmpty(String s) {
    return ((s == null) || (s.isEmpty()));
  }
}

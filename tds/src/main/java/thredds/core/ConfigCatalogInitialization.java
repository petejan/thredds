/*
 * Copyright 1998-2015 University Corporation for Atmospheric Research/Unidata
 *
 *   Portions of this software were developed by the Unidata Program at the
 *   University Corporation for Atmospheric Research.
 *
 *   Access and use of this software shall impose the following obligations
 *   and understandings on the user. The user is granted the right, without
 *   any fee or cost, to use, copy, modify, alter, enhance and distribute
 *   this software, and any derivative works thereof, and its supporting
 *   documentation for any purpose whatsoever, provided that this entire
 *   notice appears in all copies of the software, derivative works and
 *   supporting documentation.  Further, UCAR requests that the user credit
 *   UCAR/Unidata in any publications that result from the use of this
 *   software or in any product that includes this software. The names UCAR
 *   and/or Unidata, however, may not be used in any advertising or publicity
 *   to endorse or promote any products or commercial entity unless specific
 *   written permission is obtained from UCAR/Unidata. The user also
 *   understands that UCAR/Unidata is not obligated to provide the user with
 *   any support, consulting, training or assistance of any kind with regard
 *   to the use, operation and performance of this software nor to provide
 *   the user with any updates, revisions, new versions or "bug fixes."
 *
 *   THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 *   IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *   WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *   DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 *   INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 *   FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 *   NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 *   WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package thredds.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import thredds.client.catalog.Access;
import thredds.client.catalog.CatalogRef;
import thredds.client.catalog.Dataset;
import thredds.client.catalog.Service;
import thredds.server.admin.DebugCommands;
import thredds.server.catalog.*;
import thredds.server.catalog.builder.ConfigCatalogBuilder;
import thredds.server.catalog.tracker.CatalogWatcher;
import thredds.server.catalog.tracker.DatasetExt;
import thredds.server.catalog.tracker.DatasetTracker;
import thredds.server.config.TdsContext;
import thredds.server.config.ThreddsConfig;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.util.Counters;
import ucar.unidata.util.StringUtil2;
import ucar.util.prefs.PreferencesExt;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;

/**
 * Reads in the Config catalogs on startup.
 * Manges any changes while running.
 *
 * @author caron
 * @since 3/21/2015
 */
@Component("ConfigCatalogInitialization")
public class ConfigCatalogInitialization {
  static private org.slf4j.Logger logCatalogInit = org.slf4j.LoggerFactory.getLogger(ConfigCatalogInitialization.class.getName());
  static private final String ERROR = "*** ERROR: ";
  static private final boolean show = true;

  @Autowired
  private TdsContext tdsContext;  // used for  getContentDirectory, contextPath

  @Autowired
  private DataRootPathMatcher<DataRoot> dataRootPathMatcher;

  @Autowired
  private DatasetTracker datasetTracker;

  @Autowired
  private CatalogWatcher catalogWatcher;

  @Autowired
  private AllowedServices allowedServices;

  @Autowired
  private DebugCommands debugCommands;

  ///////////////////////////////////////////////////////
  private boolean useEsgfMode;
  private boolean skipTestDataDir = true;

  private List<String> rootCatalogKeys;    // needed ??
  private File contentRootPath;                   // ${tds.content.root.path}.
  private String contextPath;
  private DatasetTracker.Callback callback;
  private boolean exceedLimit = false;
  private long countDatasets = 0;
  private long maxDatasets;

  Set<String> pathHash = new HashSet<>();       // Hash of paths, to look for duplicates LOOK maybe tracker should do this
  Set<String> idHash = new HashSet<>();         // Hash of ids, to look for duplicates

  public ConfigCatalogInitialization() {
  }

  // used from outside of tomcat/spring
  public ConfigCatalogInitialization(String contentRootPath, String rootCatalog, DataRootPathMatcher<DataRoot> matcher, DatasetTracker datasetTracker,
                                     CatalogWatcher catalogWatcher,
                                     AllowedServices allowedServices, DatasetTracker.Callback callback, long maxDatasets) throws IOException {
    this.contentRootPath = new File(contentRootPath);
    this.contextPath = "/thredds";
    this.dataRootPathMatcher = matcher;
    this.datasetTracker = datasetTracker;
    this.catalogWatcher = catalogWatcher;
    this.allowedServices = allowedServices;
    this.callback = callback;
    this.maxDatasets = maxDatasets;

    skipTestDataDir = true;
    readRootCatalog(rootCatalog);
  }

  // called from TdsInit
  public void init(boolean useEsgfMode, PreferencesExt prefs) {
    callback = new StatCallback();

    long lastRead = prefs.getLong("lastRead", 0);
    logCatalogInit.info("ConfigCatalogInitializion lastRead=" + CalendarDate.of(lastRead));

    this.useEsgfMode = useEsgfMode;
    this.contentRootPath = this.tdsContext.getContentDirectory();
    this.contextPath = tdsContext.getContextPath();

    rootCatalogKeys = new ArrayList<>();
    rootCatalogKeys.add("catalog.xml"); // always first
    rootCatalogKeys.addAll(ThreddsConfig.getCatalogRoots()); // add any others listed in ThreddsConfig

    logCatalogInit.info("ConfigCatalogManage: initializing " + rootCatalogKeys.size() + " root catalogs.");

    for (String pathname : rootCatalogKeys) {
      try {
        logCatalogInit.info("\n**************************************\nCatalog init " + pathname + "[" + CalendarDate.present() + "]");
        pathname = StringUtils.cleanPath(pathname);
        readRootCatalog(pathname);
      } catch (Throwable e) {
        logCatalogInit.error(ERROR + "initializing catalog " + pathname + "; " + e.getMessage(), e);
      }
    }

    makeDebugActions();
    prefs.putLong("lastRead", System.currentTimeMillis());
    logCatalogInit.info("\nConfigCatalogInitializion stats\n" + callback);
    callback.finish();
    System.out.printf("%s%n", callback);
  }

  // root catalogs are always read
  // path must be relative to rootDir
  private void readRootCatalog(String catalogRelpath) throws IOException {
    if (exceedLimit) return;

    catalogRelpath = StringUtils.cleanPath(catalogRelpath);
    File catalogFullPath = new File(this.contentRootPath, catalogRelpath);
    if (!catalogFullPath.exists()) {
      logCatalogInit.error(ERROR + "initCatalog(): Catalog [" + catalogRelpath + "] does not exist in config directory.");
      return;
    }
    if (show) System.out.printf("initCatalog %s%n", catalogRelpath);

    // make sure we dont already have it
    if (pathHash.contains(catalogRelpath)) {
      logCatalogInit.error(ERROR + "initCatalog(): Catalog [" + catalogRelpath + "] already seen, possible loop (skip).");
      return;
    }
    pathHash.add(catalogRelpath);
    // if (logCatalogInit.isDebugEnabled()) logCatalogInit.debug("initCatalog {} -> {}", path, f.getAbsolutePath());

    // read it
    ConfigCatalog cat = readCatalog(catalogRelpath, catalogFullPath.getPath());
    if (cat == null) {
      logCatalogInit.error(ERROR + "initCatalog(): failed to read catalog <" + catalogFullPath.getPath() + ">.");
      return;
    }
    //ccc.put(path, cat);  // LOOK really ??
    if (callback != null) callback.hasCatalogRef(cat);

    // look for datasetRoots
    for (DatasetRootConfig p : cat.getDatasetRoots())
      addRoot(p, true);

    if (callback == null) {   // LOOK
      List<String> disallowedServices = allowedServices.getDisallowedServices(cat.getServices());
      if (!disallowedServices.isEmpty()) {
        allowedServices.getDisallowedServices(cat.getServices());
        logCatalogInit.error(ERROR + "initCatalog(): declared services: " + Arrays.toString(disallowedServices.toArray()) + " in catalog: " + catalogFullPath.getPath() + " are disallowed in threddsConfig file");
      }
    }

    // look for dataRoots in datasetScans and featureCollections
    extractDataRoots(cat.getDatasets());

    // get the directory path, reletive to the rootDir
    int pos = catalogRelpath.lastIndexOf("/");
    String dirPath = (pos > 0) ? catalogRelpath.substring(0, pos + 1) : "";
    processDatasets(dirPath, cat.getDatasets());     // recurse

    // look for catalogScans
    for (CatalogScan catScan : cat.getCatalogScans()) {
      if (exceedLimit) return;
      Path relLocation = Paths.get(dirPath, catScan.getLocation());
      Path absLocation = Paths.get(catalogFullPath.getParent(), catScan.getLocation());
      if (catalogWatcher != null) catalogWatcher.registerAll(absLocation);
      readCatsInDirectory(relLocation.toString(), absLocation);
    }
  }

  /**
   * Does the actual work of reading a catalog.
   *
   * @param path            reletive path starting from content root
   * @param catalogFullPath absolute location on disk
   * @return the Catalog, or null if failure
   */
  private ConfigCatalog readCatalog(String path, String catalogFullPath)  {
    URI uri;
    try {
      // uri = new URI("file:" + StringUtil2.escape(catalogFullPath, "/:-_.")); // LOOK needed ?
      uri = new URI(this.contextPath + "/catalog/" + path);
    } catch (URISyntaxException e) {
      logCatalogInit.error(ERROR + "readCatalog(): URISyntaxException=" + e.getMessage());
      return null;
    }

    ConfigCatalogBuilder builder = new ConfigCatalogBuilder();
    try {
      // read the catalog
      logCatalogInit.info("\n-------readCatalog(): full path=" + catalogFullPath + "; path=" + path+ "; uri=" + uri);
      ConfigCatalog cat = (ConfigCatalog) builder.buildFromLocation(catalogFullPath, uri);
      if (builder.hasFatalError()) {
        logCatalogInit.error(ERROR + "   invalid catalog -- " + builder.getErrorMessage());
        return null;
      }

      if (builder.getErrorMessage().length() > 0)
        logCatalogInit.debug(builder.getErrorMessage());

      return cat;

    } catch (Throwable t) {
      logCatalogInit.error(ERROR + "  Exception on catalog=" + catalogFullPath + " " + t.getMessage() + "\n log=" + builder.getErrorMessage(), t);
      return null;
    }
  }

  // dirPath = the directory path, reletive to the rootDir
  private void processDatasets(String dirPath, List<Dataset> datasets) throws IOException {
    if (exceedLimit) return;

    for (Dataset ds : datasets) {
      if (datasetTracker.trackDataset(ds, callback)) countDatasets++;
      if (maxDatasets > 0 && countDatasets > maxDatasets) exceedLimit = true;

      if ((ds instanceof DatasetScan) || (ds instanceof FeatureCollectionRef)) continue;

      if (ds instanceof CatalogRef) { // follow catalog refs
        CatalogRef catref = (CatalogRef) ds;
        String href = catref.getXlinkHref();
        // if (logCatalogInit.isDebugEnabled()) logCatalogInit.debug("  catref.getXlinkHref=" + href);

        // Check that catRef is relative
        if (!href.startsWith("http:")) {
          // Clean up relative URLs that start with "./"
          if (href.startsWith("./")) {
            href = href.substring(2);
          }

          String path;
          String contextPathPlus = this.contextPath + "/";
          if (href.startsWith(contextPathPlus)) {
            path = href.substring(contextPathPlus.length()); // absolute starting from content root
          } else if (href.startsWith("/")) {
            // Drop the catRef because it points to a non-TDS served catalog.
            logCatalogInit.error(ERROR + "Skipping catalogRef <xlink:href=" + href + ">. Reference is relative to the server outside the context path [" + contextPathPlus + "]. " +
                    "Parent catalog info: Name=\"" + catref.getParentCatalog().getName() + "\"; Base URI=\"" + catref.getParentCatalog().getUriString() + "\"; dirPath=\"" + dirPath + "\".");
            continue;
          } else {
            path = dirPath + href;  // reletive starting from current directory
          }

          readRootCatalog(path); // LOOK
        }

      } else {
        // recurse through nested datasets
        processDatasets(dirPath, ds.getDatasets());
      }
    }
  }

  // dirPath is the directory relative to rootDir, directory is absolute
  private void readCatsInDirectory(String dirPath, Path directory) throws IOException {
    if (exceedLimit) return;

     // do any catalogs first
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory, "*.xml")) {
      for (Path p : ds) {
        if (!Files.isDirectory(p)) {
          // path must be relative to rootDir
          String filename = p.getFileName().toString();
          String path = dirPath.length() == 0 ? filename :  dirPath + "/" + filename;  // reletive starting from current directory
          readRootCatalog(path); // LOOK
        }
      }
    }

    // now recurse into the directory
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory)) {
       for (Path dir : ds) {
         if (Files.isDirectory(dir)) {
           String dirPathChild = dirPath + "/" + dir.getFileName().toString();  // reletive starting from current directory
           readCatsInDirectory(dirPathChild, dir);
         }
       }
     }

   }

  /**
   * Finds datasetScan, datasetFmrc
   * Look for duplicate Ids (give message). Dont follow catRefs.
   *
   * @param dsList the list of Dataset
   */
  private void extractDataRoots(List<Dataset> dsList) {

    for (Dataset dataset : dsList) {
      // look for duplicate ids
      String id = dataset.getID();
      if (id != null) {
        if (idHash.contains(id)) {
          logCatalogInit.error(ERROR + "Duplicate id on  '" + dataset.getName() + "' id= '" + id + "'");
        } else {
          idHash.add(id);
        }
      }

      if (dataset instanceof DatasetScan) {
        DatasetScan ds = (DatasetScan) dataset;
        Service service = ds.getServiceDefault();
        if (service == null) {
          logCatalogInit.error(ERROR + "DatasetScan " + ds.getName() + " has no default Service - skipping");  // LOOK needed?
          continue;
        }
        addRoot(ds);

      } else if (dataset instanceof FeatureCollectionRef) {
        FeatureCollectionRef fc = (FeatureCollectionRef) dataset;
        addRoot(fc);

      }  else if (dataset instanceof CatalogScan) {
        CatalogScan catScan = (CatalogScan) dataset;
        addRoot(catScan);
      }

      if (!(dataset instanceof CatalogRef)) {
        // recurse
        extractDataRoots(dataset.getDatasets());
      }
    }

  }

  private boolean addRoot(DatasetScan dscan) {
    // check for duplicates
    String path = dscan.getPath();

    if (path == null) {
      logCatalogInit.error(ERROR + "DatasetScan '" + dscan.getName() + "' missing the path attribute.");
      return false;
    }

    DataRoot droot = dataRootPathMatcher.get(path);
    if (droot != null) {
      if (!droot.getDirLocation().equals(dscan.getScanLocation())) {
        logCatalogInit.error(ERROR + "DatasetScan already have dataRoot =<" + path + ">  mapped to directory= <" + droot.getDirLocation() + ">" +
                " wanted to map to =<" + dscan.getScanLocation() + "> in catalog " + dscan.getParentCatalog().getUriString());
      }
      return false;
    }

    // add it
    droot = new DataRoot(dscan);
    dataRootPathMatcher.put(path, droot);
    if (callback != null) callback.hasDataRoot(droot);
    logCatalogInit.debug(" added rootPath=<" + path + ">  for directory= <" + dscan.getScanLocation() + ">");
    return true;
  }

  private boolean addRoot(FeatureCollectionRef fc) {
    // check for duplicates
    String path = fc.getPath();

    if (path == null) {
      logCatalogInit.error(ERROR + "FeatureCollection '"+ fc.getName() + "' missing the path attribute.");
      return false;
    }

    DataRoot droot = dataRootPathMatcher.get(path);
    if (droot != null) {
      logCatalogInit.error(ERROR + "FeatureCollection already have dataRoot =<" + path + ">  mapped to directory= <" + droot.getDirLocation() + ">" +
              " wanted to use by FeatureCollection Dataset =<" + fc.getName() + ">");
      return false;
    }

    // add it
    droot = new DataRoot(fc);

    if (droot.getDirLocation() != null) {
      File file = new File(droot.getDirLocation());
      if (!skipTestDataDir && !file.exists()) {
        logCatalogInit.error(ERROR + "FeatureCollection = '" + fc.getName() + "' directory= <" + droot.getDirLocation() + "> does not exist\n");
        return false;
      }
    }

    dataRootPathMatcher.put(path, droot);
    if (callback != null) callback.hasDataRoot(droot);
    logCatalogInit.debug(" added rootPath=<" + path + ">  for feature collection= <" + fc.getName() + ">");
    return true;
  }

  private boolean addRoot(DatasetRootConfig config, boolean wantErr) {
    String path = config.getPath();
    String location = config.getLocation();

    // check for duplicates
    DataRoot droot = dataRootPathMatcher.get(path);
    if (droot != null) {
      if (wantErr)
        logCatalogInit.error(ERROR + "DatasetRootConfig already have dataRoot =<" + path + ">  mapped to directory= <" + droot.getDirLocation() + ">" +
                " wanted to map to <" + location + ">");

      return false;
    }

    location = DataRootAlias.translateAlias(location);
    File file = new File(location);
    if (!skipTestDataDir && !file.exists()) {
      logCatalogInit.error(ERROR + "DatasetRootConfig path ='" + path + "' directory= <" + location + "> does not exist");
      return false;
    }

    // add it
    droot = new DataRoot(path, location);
    dataRootPathMatcher.put(path, droot);
    if (callback != null) callback.hasDataRoot(droot);
    logCatalogInit.debug(" added rootPath=<" + path + ">  for directory= <" + location + ">");
    return true;
  }

  private boolean addRoot(CatalogScan catScan) {
    // check for duplicates
    String path = catScan.getPath();

    if (path == null) {
      logCatalogInit.error(ERROR + "CatalogScan '"+ catScan.getName() + "' missing the location attribute.");
      return false;
    }

    DataRoot droot = dataRootPathMatcher.get(path);
    if (droot != null) {
      logCatalogInit.error(ERROR + "already have dataRoot =<" + path + ">  mapped to directory= <" + droot.getDirLocation() + ">" +
              " wanted to use by CatalogScan =<" + catScan.getName() + ">");
      return false;
    }

    // add it
    droot = new DataRoot(catScan);

    if (droot.getDirLocation() != null) {
      File file = new File(droot.getDirLocation());
      if (!skipTestDataDir && !file.exists()) {
        logCatalogInit.error(ERROR + "FeatureCollection = '" + catScan.getName() + "' directory= <" + droot.getDirLocation() + "> does not exist\n");
        return false;
      }
    }

    dataRootPathMatcher.put(path, droot);
    if (callback != null) callback.hasDataRoot(droot);
    logCatalogInit.debug(" added rootPath=<" + path + ">  for feature collection= <" + catScan.getName() + ">");
    return true;
  }



  /////////////////////////////////////////////////////

  public void makeDebugActions() {
    DebugCommands.Category debugHandler = debugCommands.findCategory("catalogs");
    DebugCommands.Action act;

    act = new DebugCommands.Action("showStatic", "Show root catalogs") {
      public void doAction(DebugCommands.Event e) {
        StringBuilder sbuff = new StringBuilder();
        synchronized (ConfigCatalogInitialization.this) {
          for (String catPath : rootCatalogKeys) {
            sbuff.append(" catalog= ").append(catPath).append("\n");
            //String filename = StringUtil2.unescape(cat.getUriString());
            //sbuff.append(" from= ").append(filename).append("\n");
          }
        }
        e.pw.println(StringUtil2.quoteHtmlContent("\n" + sbuff.toString()));
      }
    };

    act = new DebugCommands.Action("showStatic", "Show catalog initialization stats") {
      public void doAction(DebugCommands.Event e) {
        if (callback != null)
          e.pw.println(StringUtil2.quoteHtmlContent("\n" + callback.toString()));
        else
          e.pw.printf("N/A%n");
      }
    };
    debugHandler.addAction(act);
  }

  /////////////////////////////////////////////////////////
  static class Stats {
    int catrefs;
    int datasets;
    int dataRoot, dataRootFc, dataRootScan, dataRootDir;
    int ncml, ncmlOne;
    int restrict;
    Counters counters = new Counters();

    public Stats() {
      counters.add("restrict");
      counters.add("nAccess");
      counters.add("serviceType");
      counters.add("ncmlAggSize");
    }

    String show(Formatter f) {
      f.format("   catrefs=%d%n", catrefs);
      f.format("  datasets=%d%n", datasets);
      f.format("  restrict=%d%n", restrict);
      f.format("  ncml=%d ncmlOne=%d%n", ncml, ncmlOne);
      f.format("  dataRoot=%d%n", dataRoot);
      f.format("    dataRootFc=%d%n", dataRootFc);
      f.format("    dataRootScan=%d%n", dataRootScan);
      f.format("    dataRootDir=%d%n", dataRootDir);
      f.format("DatasetExt.total_count %d%n", DatasetExt.total_count);
      f.format("DatasetExt.total_nbytes %d%n", DatasetExt.total_nbytes);
      float avg = DatasetExt.total_count == 0 ? 0 : ((float) DatasetExt.total_nbytes) / DatasetExt.total_count;
      f.format("DatasetExt.avg_nbytes %5.0f%n", avg);
      counters.show(f);
      return f.toString();
    }
  }

  static class StatCallback implements DatasetTracker.Callback {
    Stats stat2;
    long start = System.currentTimeMillis();
    double took;

    StatCallback() {
      stat2 = new Stats();
    }

    @Override
    public void finish() {
      took = (System.currentTimeMillis() - start) / 1000.0;
    }

    @Override
    public void hasDataRoot(DataRoot dataRoot) {
      stat2.dataRoot++;
      if (dataRoot.getFeatureCollection() != null) stat2.dataRootFc++;
      if (dataRoot.getDatasetScan() != null) stat2.dataRootScan++;
      if (dataRoot.getDirLocation() != null) stat2.dataRootDir++;
    }

    @Override
    public void hasDataset(Dataset ds) {
      stat2.datasets++;
      List<Access> access = ds.getAccess();
      stat2.counters.count("nAccess", access.size());
      for (Access acc : access)
        if (acc.getService() != null) stat2.counters.count("serviceType", acc.getService().toString());
    }

    @Override
    public void hasNcml(Dataset ds) {
      stat2.ncml++;
      org.jdom2.Element netcdfElem = ds.getNcmlElement();
      org.jdom2.Element agg =  netcdfElem.getChild("aggregation", thredds.client.catalog.Catalog.ncmlNS);
      if (agg == null) return;
      List<org.jdom2.Element> nested =  agg.getChildren("netcdf", thredds.client.catalog.Catalog.ncmlNS);
      if (nested == null) return;
      if (nested.size() == 1)
        stat2.ncmlOne++;
      // look for nested ncml - count how many
      stat2.counters.count("ncmlAggSize", nested.size());
    }

    @Override
    public void hasRestriction(Dataset ds) {
      stat2.restrict++;
      String restrict = ds.getRestrictAccess();
      if (restrict != null) stat2.counters.count("restrict", restrict);
    }

    @Override
    public void hasCatalogRef(ConfigCatalog dd) {
      stat2.catrefs++;
    }

    @Override
    public String toString() {
      Formatter f = new Formatter();
      System.out.printf("ConfigCatalogInitialization took %f secs%n", took);
      return stat2.show(f);
    }
  }
}

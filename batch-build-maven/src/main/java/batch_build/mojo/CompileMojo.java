package batch_build.mojo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.corn.cps.CPScanner;
import net.sf.corn.cps.ResourceFilter;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.cli.CliDriver;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hive.hcatalog.common.HCatUtil;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.thrift.TException;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import batch_build.common.model.BatchModel;
import batch_build.common.model.resources.FileLocationResource;
import batch_build.common.model.resources.HCatResource;
import batch_build.common.model.resources.Resource;
import batch_build.common.model.resources.HCatResource.HCatColumn;
import batch_build.common.model.tasks.HiveTask;
import batch_build.common.model.tasks.LinkedTask;
import batch_build.common.model.tasks.PigTask;
import batch_build.common.model.tasks.Task;
import batch_build.mojo.utils.TreeNode;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

@Mojo(requiresProject = true, name = "compile", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class CompileMojo extends AbstractMojo {

	private List<String> classpathElements;

	@Parameter(defaultValue = "${project.build.directory}/tmp", readonly = true, required = true)
	private File tmpDir;
	
	@Parameter(defaultValue = "${project.build.directory}/docs", readonly = true, required = true)
	private File reportDir;
	
	@Parameter(defaultValue = "${project.build.directory}/model.json", readonly = true, required = true)
	private File modelFile;
	
	@Parameter(defaultValue = "${basedir}/tasks", readonly = true, required = true)
	private File tasksDir;
	
	@Parameter(defaultValue = "${basedir}/tables", readonly = true, required = true)
	private File tablesDir;
	
	@Parameter(defaultValue = "${project}", required=true)
	private MavenProject project;

	
	private BatchModel model;
	
	private List<Task> unlinkedTasks;

	public void execute() throws MojoExecutionException {
		try {
			setupMavenResources();
			clean();
			setupClassLoader();
			setupHadoop(tmpDir);
			model.getResources().putAll(resourcesToMap(createHiveTables()));
			parseTasks(tasksDir);
			model.setTasks(optimizeDeps(unlinkedTasks));
			generateReports();
			model.writeModel(modelFile);
		} catch (Throwable e) {
			throw new MojoExecutionException("Problem running mojo", e);
		}
	}
	
	private void setupMavenResources() throws DependencyResolutionRequiredException, IOException{
		classpathElements = project.getCompileClasspathElements();
		FileUtils.copyDirectory(tasksDir, new File(project.getBuild().getOutputDirectory(), "tasks"));
		FileUtils.copyDirectory(tablesDir, new File(project.getBuild().getOutputDirectory(), "tables"));
	}
	
	private void generateReports() throws Exception {
		// recreate tree structure for tasks
		TreeNode<String> tasksRoot = new TreeNode<String>(null, "Tasks");
		for (LinkedTask t : model.getTasks()){
			TreeNode<String> node = tasksRoot;
			String taskName = t.getTask().getName();
			String[] pathComponents = taskName.split("/");
			for (String pathComponent : pathComponents){
				node = node.getOrCreateChild(pathComponent);
			}
			node.setItem(UrlHelper.INSTANCE.urlFromTaskName(taskName));
		}
		
		// create tree structure for tables
		TreeNode<String> tablesRoot = new TreeNode<String>(null, "HCat Tables");
		for (Resource r : model.getResources().values()){
			if (r instanceof HCatResource){
				HCatResource table = (HCatResource) r;
				TreeNode<String> dbNode = tablesRoot.getOrCreateChild(table.getDbName());
				dbNode.addChild(new TreeNode<>(UrlHelper.INSTANCE.urlFromResource(table), table.getTableName()));
			}
		}
		tablesRoot.sortChildren(true);
		
		Multimap<String, Task> readUsage = ArrayListMultimap.create();
		Multimap<String, Task> writeUsage = ArrayListMultimap.create();
		
		// Find Usages
		for (Task t : unlinkedTasks){
			for (String r : t.getSinkResources()){
				writeUsage.put(r, t);
			}
			for (String r : t.getSourceResources()){
				readUsage.put(r, t);
			}
		}
		
		reportDir.mkdirs();
		File reportTablesDir = new File(reportDir, "tables");
		reportTablesDir.mkdirs();
		File reportTasksDir = new File(reportDir, "tasks");
		reportTasksDir.mkdirs();
		VelocityEngine ve = new VelocityEngine();
		ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
		ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
		ve.setProperty("runtime.log.logsystem.class", "org.apache.velocity.runtime.log.NullLogSystem");
		
		// Tables
		Template tableTemplate = ve.getTemplate("templates/tableDoc.vm");
		for (Resource r : model.getResources().values()){
			if (r instanceof HCatResource){
				HCatResource resource = (HCatResource) r;
				String fileName = resource.getDbName() + "/" + resource.getTableName() + ".html";
				VelocityContext context = new VelocityContext();
				context.put("urlHelper", UrlHelper.INSTANCE);
				context.put("table", r);
				context.put("taskTree", tasksRoot);
				context.put("tablesTree", tablesRoot);
				context.put("baseDir", "../../");
				context.put("readUsages", readUsage.get(resource.getUniqueIdentifier()));
				context.put("writeUsages", writeUsage.get(resource.getUniqueIdentifier()));
				File file = new File(reportTablesDir, fileName);
				file.getParentFile().mkdirs();
				FileWriter writer = new FileWriter(file);
				tableTemplate.merge(context, writer);
				writer.close();
			}
		}
		
		// Tasks
		Template taskTemplate = ve.getTemplate("templates/taskDoc.vm");
		for (LinkedTask task : model.getTasks()){
			List<Resource> sinkResources = new ArrayList<>();
			for (String resourceId : task.getTask().getSinkResources()){
				sinkResources.add(model.getResources().get(resourceId));
			}
			List<Resource> sourceResources = new ArrayList<>();
			for (String resourceId : task.getTask().getSourceResources()){
				sourceResources.add(model.getResources().get(resourceId));
			}
			String fileName = task.getTask().getName() + ".html";
			String baseDir = StringUtils.repeat("../", fileName.split("/").length);
			VelocityContext context = new VelocityContext();
			context.put("urlHelper", UrlHelper.INSTANCE);
			context.put("task", task);
			context.put("taskTree", tasksRoot);
			context.put("tablesTree", tablesRoot);
			context.put("baseDir", baseDir);
			context.put("sourceResources", sourceResources);
			context.put("sinkResources", sinkResources);
			File htmlFile = new File(reportTasksDir, fileName);
			htmlFile.getParentFile().mkdirs();
			FileWriter writer = new FileWriter(htmlFile);
			taskTemplate.merge(context, writer);
			writer.close();
			
		}
		
		// TaskGraph
		Template graphTemplate = ve.getTemplate("templates/taskGraph.vm");
		VelocityContext context = new VelocityContext();
		context.put("urlHelper", UrlHelper.INSTANCE);
		context.put("tasks", model.getTasks());
		context.put("taskTree", tasksRoot);
		context.put("tablesTree", tablesRoot);
		context.put("baseDir", "");
		FileWriter writer = new FileWriter(new File(reportDir, "index.html"));
		graphTemplate.merge(context, writer);
		writer.close();
	}


	private void clean() throws IOException {
		model = new  BatchModel();
		unlinkedTasks = new ArrayList<>();
		FileUtils.deleteDirectory(tmpDir);
		FileUtils.deleteDirectory(reportDir);
	}
	
	
	private void setupClassLoader() throws MalformedURLException, ClassNotFoundException {
		// Dirty hack to prevent against class not found error caused by mavens classloaders
		// being shutdown before the shutdownhooks have been run
		Class.forName("org.apache.hadoop.util.ShutdownHookManager$2");
		// These are only for hive/pig 0.12
		Class.forName("com.google.common.cache.LocalCache$Values");
		Class.forName("com.google.common.cache.LocalCache$ValueIterator");
		Class.forName("com.google.common.cache.LocalCache$WriteThroughEntry");
		
		URL[] urls = new URL[classpathElements.size()];
		for (int i = 0; i < classpathElements.size(); i++) {
			urls[i] = new File(classpathElements.get(i)).toURI().toURL();
		}
		URLClassLoader cl = URLClassLoader.newInstance(urls, Thread.currentThread().getContextClassLoader());
		Thread.currentThread().setContextClassLoader(cl);
	}
	
	
	static void setupHadoop(File tmpDir) throws IOException {
		tmpDir.mkdirs();
		
		//System.setProperty("hadoop.bin.path","");
		System.setProperty("target.tmp", tmpDir.getAbsolutePath());
		System.setProperty("pig.temp.dir", tmpDir.getAbsolutePath());
		System.setProperty("derby.stream.error.file", tmpDir.getAbsolutePath()
				+ "/derby.log");
		System.setProperty("hive.semantic.analyzer.hook", HiveExplainHook.class.getName());

		File hadoopHome = new File(tmpDir, "hadoop");
		File hadoopBin = new File(hadoopHome, "bin");
		hadoopBin.mkdirs();
		System.setProperty("hadoop.home.dir", hadoopHome.getAbsolutePath());
		FileUtils.copyInputStreamToFile(Thread.currentThread()
				.getContextClassLoader().getResourceAsStream("winutils.exe"),
				new File(hadoopBin, "winutils.exe"));
	}


	private List<HCatResource> createHiveTables() throws IOException,
			InvalidObjectException, MetaException, TException, SQLException {
		HiveMetaStoreClient hiveClient = HCatUtil.getHiveClient(new HiveConf(
				CompileMojo.class));
		SessionState.start(createNewSessionState());
		CliDriver hiveCli = new CliDriver();
		List<URL> tableUrls = CPScanner.scanResources(new ResourceFilter().packageName("tables.*").resourceName("*.hql"));
		Set<String> dbsCreated = new HashSet<>();
		Map<String,String> tableNameToSource = new HashMap<>();
		List<String> badFiles = new ArrayList<>();
		for (URL url : tableUrls) {
			String fileName = url.getFile();
			System.out.println(fileName);
			String[] pathComponents = fileName.split("/");
			String dbName = pathComponents[pathComponents.length -2];
			if (dbsCreated.add(dbName)){
				hiveClient.createDatabase(new Database(dbName, null, null, null));
			}
			System.out.println("Running " + fileName);
			SessionState.start(createNewSessionState());
			HiveExplainHook.reset();
			if (hiveCli.processReader(new BufferedReader(new InputStreamReader(url.openStream()))) != 0) {
				badFiles.add(fileName);
			}
			String source = IOUtils.toString(url.openStream());
			for (String tableName : HiveExplainHook.getTablesCreated()){
				tableNameToSource.put(tableName, source);
			}
			
		}
		if (!badFiles.isEmpty()) {
			throw new RuntimeException("Error creating tables in files "
					+ badFiles);
		}
		List<HCatResource> tables = new ArrayList<>();

		for (String dbName : hiveClient.getAllDatabases()) {
			for (String tableName : hiveClient.getAllTables(dbName)) {
				Table table = hiveClient.getTable(dbName, tableName);
				List<FieldSchema> fields = hiveClient.getFields(dbName,
						tableName);
				List<FieldSchema> partitionFields = table.getPartitionKeys();
				List<HCatColumn> columns = new ArrayList<>();
				for (FieldSchema field : fields) {
					columns.add(new HCatColumn(field.getType(),
							field.getName(), field.getComment(), false));
				}
				for (FieldSchema field : partitionFields) {
					columns.add(new HCatColumn(field.getType(),
							field.getName(), field.getComment(), true));
				}
				String source = tableNameToSource.get(dbName + "." + tableName);
				tables.add(new HCatResource(dbName, tableName, table
						.getParameters().get("comment"), source, columns));
			}
		}
		return tables;
	}
	
	private void parseTasks(File dir) throws Throwable{
		File[] files = dir.listFiles();
		if (files == null){
			return;
		}
		Arrays.sort(files);
		for (File file : files){
			if (file.isDirectory()){
				parseTasks(file);
			} else if (file.isFile()){
				String taskName = tasksDir.toPath().relativize(file.toPath()).toString().replace("\\", "/");
				if (file.getName().endsWith(".pig")){
					//unlinkedTasks.add(explainPigTask(taskName, file));
				}else if (file.getName().endsWith(".hql")){
					unlinkedTasks.add(explainHiveTask(taskName, file));
				}
			}
		}
	}

	private HiveTask explainHiveTask(String taskName, File hiveScript) throws FileNotFoundException, IOException{
		System.out.println("Explaining " + taskName);
		SessionState.start(createNewSessionState());
		CliDriver hiveCli = new CliDriver();
		HiveExplainHook.reset();
		if (hiveCli.processReader(new BufferedReader(new FileReader(hiveScript))) !=0){
			throw new RuntimeException("Failed to explain hive query");
		}
		
		return new HiveTask(taskName, HiveExplainHook.getSources(), HiveExplainHook.getSinks(), FileUtils.readFileToString(hiveScript));
	}
	
//	private PigTask explainPigTask(String taskName, File pigScript) throws Throwable {
//		System.out.println("Explaining " + taskName);
//		PigServer pig = new PigServer(ExecType.LOCAL);
//		try {
//			pig.registerQuery(FileUtils.readFileToString(pigScript));
//			LogicalPlanData logicalPlanData = pig.getLogicalPlanData();
//			List<String> sinks = logicalPlanData.getSinks();
//			List<String> storeFuncts = logicalPlanData.getStoreFuncs();
//			Set<String> destResources = new HashSet<>();
//			String resourceId;
//			for (int i = 0; i < logicalPlanData.getSinks().size(); i++) {
//				switch (storeFuncts.get(i)) {
//				case "org.apache.pig.builtin.PigStorage":
//					FileLocationResource resource = new FileLocationResource(
//							sinks.get(i));
//					model.getResources().put(resource.getUniqueIdentifier(), resource);
//					resourceId = resource.getUniqueIdentifier();
//					break;
//				case "org.apache.hive.hcatalog.pig.HCatStorer":
//					resourceId = "hcat:" + sinks.get(i);
//					break;
//				default:
//					throw new RuntimeException("Unknown pig storage:"
//							+ storeFuncts.get(i));
//				}
//				assert model.getResources().containsKey(resourceId);
//				destResources.add(resourceId);
//			}
//
//			List<String> sources = logicalPlanData.getSources();
//			List<String> loadFuncts = logicalPlanData.getLoadFuncs();
//			Set<String> sourceResources = new HashSet<>();
//			for (int i = 0; i < logicalPlanData.getSources().size(); i++) {
//				switch (loadFuncts.get(i)) {
//				case "org.apache.pig.builtin.PigStorage":
//					FileLocationResource resource = new FileLocationResource(
//							sources.get(i));
//					model.getResources().put(resource.getUniqueIdentifier(), resource);
//					resourceId = resource.getUniqueIdentifier();
//					break;
//				case "org.apache.hive.hcatalog.pig.HCatLoader":
//					resourceId = "hcat:" + sources.get(i);
//					break;
//				default:
//					throw new RuntimeException("Unknown pig loader:"
//							+ loadFuncts.get(i));
//				}
//				assert model.getResources().containsKey(resourceId);
//				sourceResources.add(resourceId);
//			}			
//			return new PigTask(taskName, sourceResources,
//					destResources, FileUtils.readFileToString(pigScript));
//		} finally {
//			pig.shutdown();
//		}
//	}

	/**
	 * Work out the best dependencies between the tasks.
	 * tasks that write resources should depend on anything before them that read or write that resource
	 * tasks that read resources should depend on tasks before them that write into the resource
	 */
	private List<LinkedTask> optimizeDeps(List<Task> tasks){
		List<LinkedTask> linkedTasks = new ArrayList<>();
		// Tracking of resource -> reads and writes
		Map<String, Set<LinkedTask>> upstreamReads = new HashMap<>();
		Map<String, LinkedTask> upstreamWrites = new HashMap<>();
		// Populate reads map with empty sets
		for (String resource : model.getResources().keySet()){
			upstreamReads.put(resource, new HashSet<LinkedTask>());
		}
		
		// First pass at calculating deps
		for (Task task : tasks){
			LinkedTask lTask = new LinkedTask(task);
			for(String resource : task.getSourceResources()){
				LinkedTask upstreamWrite = upstreamWrites.get(resource);
				if (upstreamWrite !=null){
					lTask.parents.add(upstreamWrite);
				}
			}
			
			for(String resource : task.getSinkResources()){
				LinkedTask upstreamWrite = upstreamWrites.get(resource);
				if (upstreamWrite !=null){
					lTask.parents.add(upstreamWrite);
				}
				lTask.parents.addAll(upstreamReads.get(resource));
			}
			
			// Setup for next task
			for (String resource : task.getSourceResources()){
				upstreamReads.get(resource).add(lTask);
			}
			for(String resource : task.getSinkResources()){
				// We can mark this as the upstream write for the following tasks
				// Reads can be cleared at this stage as writes trump reads
				upstreamWrites.put(resource, lTask);
				upstreamReads.get(resource).clear();
			}
			linkedTasks.add(lTask);
		}
		
		// second pass requires removing redundant deps
		// We can achieve this by testing each dep to see if we can reach it from other dep
		for (LinkedTask task : linkedTasks){
			Iterator<LinkedTask> deps = task.parents.iterator();
			deploop: while (deps.hasNext()){
				LinkedTask dep = deps.next();
				// Take a copy to prevent concurrent modification exceptions etc
				for (LinkedTask t : new ArrayList<>(task.parents)){
					if (t == dep){
						continue;
					}
					if (t.isDependentOn(dep)){
						deps.remove();
						continue deploop;
					}
				}
				
			}
		}
		return linkedTasks;
	}
	

	private static CliSessionState createNewSessionState() {
		CliSessionState ss = new CliSessionState(new HiveConf(
				CompileMojo.class));
		ss.getConf().set("batch_id", "1000");
		ss.err = System.err;
		ss.out = System.err;
		ss.childErr = System.err;
		ss.setIsSilent(false);
		return ss;
	}

	

	private static <T extends Resource> Map<String, T> resourcesToMap(
			List<T> resources) {
		Map<String, T> result = new HashMap<>();
		for (T resource : resources) {
			result.put(resource.getUniqueIdentifier(), resource);
		}

		return result;
	}
}

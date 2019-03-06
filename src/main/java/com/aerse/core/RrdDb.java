package com.aerse.core;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.Date;

import com.aerse.ConsolFun;

/**
 * <p>
 * Main class used to create and manipulate round robin databases (RRDs). Use
 * this class to perform update and fetch operations on existing RRDs, to create
 * new RRD from the definition (object of class {@link com.aerse.core.RrdDef
 * RrdDef}).
 * </p>
 * <p>
 * Each RRD is backed with some kind of storage. For example, RRDTool supports
 * only one kind of storage (disk file). On the contrary, Rrd4j gives you
 * freedom to use other storage (backend) types even to create your own backend
 * types for some special purposes. Rrd4j by default stores RRD data in files
 * (as RRDTool), but you might choose to store RRD data in memory (this is
 * supported in Rrd4j), to use java.nio.* instead of java.io.* package for file
 * manipulation (also supported) or to store whole RRDs in the SQL database
 * (you'll have to extend some classes to do this).
 * </p>
 * <p>
 * Note that Rrd4j uses binary format different from RRDTool's format. You
 * cannot use this class to manipulate RRD files created with RRDTool.
 * <b>However, if you perform the same sequence of create, update and fetch
 * operations, you will get exactly the same results from Rrd4j and RRDTool.</b>
 * </p>
 * <p>
 * You will not be able to use Rrd4j API if you are not familiar with basic
 * RRDTool concepts. Good place to start is the <a href=
 * "http://people.ee.ethz.ch/~oetiker/webtools/rrdtool/tutorial/rrdtutorial.html"
 * >official RRD tutorial</a> and relevant RRDTool man pages: <a
 * href="../../../../man/rrdcreate.html" target="man">rrdcreate</a>, <a
 * href="../../../../man/rrdupdate.html" target="man">rrdupdate</a>, <a
 * href="../../../../man/rrdfetch.html" target="man">rrdfetch</a> and <a
 * href="../../../../man/rrdgraph.html" target="man">rrdgraph</a>. For RRDTool's
 * advanced graphing capabilities (RPN extensions), also supported in Rrd4j,
 * there is an excellent <a
 * href="http://oss.oetiker.ch/rrdtool/tut/cdeftutorial.en.html"
 * target="man">CDEF tutorial</a>.
 * </p>
 *
 * @see RrdBackend
 * @see RrdBackendFactory
 */
public class RrdDb implements RrdUpdater, Closeable {

	/**
	 * Prefix to identify external RRDTool file source used in various RrdDb
	 * constructors.
	 */
	public static final String PREFIX_RRDTool = "rrdtool:/";

	// static final String RRDTOOL = "rrdtool";
	static final int XML_BUFFER_CAPACITY = 100000; // bytes

	private RrdBackend backend;
	private RrdAllocator allocator = new RrdAllocator();

	private final Header header;
	private final Datasource[] datasources;
	private final Archive[] archives;

	private boolean closed = false;

	/**
	 * <p>
	 * Constructor used to create new RRD object from the definition. If the
	 * rrdDef was constructed giving an {@link java.net.URI},
	 * {@link com.aerse.core.RrdBackendFactory#findFactory(URI)} will be used to
	 * resolve the needed factory. If not, or a relative URI was given, this RRD
	 * object will be backed with a storage (backend) of the default type.
	 * Initially, storage type defaults to "NIO" (RRD bytes will be put in a
	 * file on the disk). Default storage type can be changed with a static
	 * {@link com.aerse.core.RrdBackendFactory#setDefaultFactory(String)} method
	 * call.
	 * </p>
	 * <p>
	 * New RRD file structure is specified with an object of class
	 * {@link RrdDef <b>RrdDef</b>}. The underlying RRD storage is created as
	 * soon as the constructor returns.
	 * </p>
	 * <p>
	 * Typical scenario:
	 * </p>
	 * 
	 * <pre>
	 * // create new RRD definition
	 * RrdDef def = new RrdDef(&quot;test.rrd&quot;, 300);
	 * def.addDatasource(&quot;input&quot;, DsType.DT_COUNTER, 600, 0, Double.NaN);
	 * def.addDatasource(&quot;output&quot;, DsType.DT_COUNTER, 600, 0, Double.NaN);
	 * def.addArchive(ConsolFun.CF_AVERAGE, 0.5, 1, 600);
	 * def.addArchive(ConsolFun.CF_AVERAGE, 0.5, 6, 700);
	 * def.addArchive(ConsolFun.CF_AVERAGE, 0.5, 24, 797);
	 * def.addArchive(ConsolFun.CF_AVERAGE, 0.5, 288, 775);
	 * def.addArchive(ConsolFun.CF_MAX, 0.5, 1, 600);
	 * def.addArchive(ConsolFun.CF_MAX, 0.5, 6, 700);
	 * def.addArchive(ConsolFun.CF_MAX, 0.5, 24, 797);
	 * def.addArchive(ConsolFun.CF_MAX, 0.5, 288, 775);
	 * 
	 * // RRD definition is now completed, create the database!
	 * RrdDb rrd = new RrdDb(def);
	 * // new RRD file has been created on your disk
	 * </pre>
	 *
	 * @param rrdDef
	 *            Object describing the structure of the new RRD file.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public RrdDb(RrdDef rrdDef) throws IOException {
		this(rrdDef, null);
	}

	/**
	 * <p>
	 * Constructor used to create new RRD object from the definition object but
	 * with a storage (backend) different from default.
	 * </p>
	 *
	 * <p>
	 * Rrd4j uses <i>factories</i> to create RRD backend objects. There are
	 * three different backend factories supplied with Rrd4j, and each factory
	 * has its unique name:
	 * </p>
	 * <ul>
	 * <li><b>FILE</b>: backends created from this factory will store RRD data
	 * to files by using java.io.* classes and methods
	 * <li><b>NIO</b>: backends created from this factory will store RRD data to
	 * files by using java.nio.* classes and methods
	 * <li><b>MEMORY</b>: backends created from this factory will store RRD data
	 * in memory. This might be useful in runtime environments which prohibit
	 * disk utilization, or for storing temporary, non-critical data (it gets
	 * lost as soon as JVM exits).
	 * </ul>
	 * <p>
	 * For example, to create RRD in memory, use the following code:
	 * </p>
	 * 
	 * <pre>
	 * RrdBackendFactory factory = RrdBackendFactory.getFactory(&quot;MEMORY&quot;);
	 * RrdDb rrdDb = new RrdDb(rrdDef, factory);
	 * rrdDb.close();
	 * </pre>
	 * <p>
	 * New RRD file structure is specified with an object of class
	 * {@link RrdDef <b>RrdDef</b>}. The underlying RRD storage is created as
	 * soon as the constructor returns.
	 * </p>
	 *
	 * @param rrdDef
	 *            RRD definition object
	 * @param factory
	 *            The factory which will be used to create storage for this RRD
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 * @see RrdBackendFactory
	 */
	public RrdDb(RrdDef rrdDef, RrdBackendFactory factory) throws IOException {

		factory = checkFactory(rrdDef.getUri(), factory);

		if (!rrdDef.hasDatasources()) {
			throw new IllegalArgumentException("No RRD datasource specified. At least one is needed.");
		}
		if (!rrdDef.hasArchives()) {
			throw new IllegalArgumentException("No RRD archive specified. At least one is needed.");
		}

		backend = factory.open(rrdDef.getUri(), false);
		backend.setFactory(factory);
		try {
			backend.setLength(rrdDef.getEstimatedSize());
			// create header
			header = new Header(this, rrdDef);
			// create datasources
			DsDef[] dsDefs = rrdDef.getDsDefs();
			datasources = new Datasource[dsDefs.length];
			for (int i = 0; i < dsDefs.length; i++) {
				datasources[i] = new Datasource(this, dsDefs[i]);
			}
			// create archives
			ArcDef[] arcDefs = rrdDef.getArcDefs();
			archives = new Archive[arcDefs.length];
			for (int i = 0; i < arcDefs.length; i++) {
				archives[i] = new Archive(this, arcDefs[i]);
			}
		} catch (IOException e) {
			backend.close();
			throw e;
		}
	}

	/**
	 * <p>
	 * Constructor used to open already existing RRD. The path will be parsed as
	 * an URI and checked against the active factories. If it's a relative URI
	 * (no scheme given, or just a plain path), the default factory will be
	 * used.
	 * </p>
	 * <p>
	 * Constructor obtains read or read/write access to this RRD.
	 * </p>
	 *
	 * @param path
	 *            Path to existing RRD.
	 * @param readOnly
	 *            Should be set to <code>false</code> if you want to update the
	 *            underlying RRD. If you want just to fetch data from the RRD
	 *            file (read-only access), specify <code>true</code>. If you try
	 *            to update RRD file open in read-only mode (
	 *            <code>readOnly</code> set to <code>true</code>),
	 *            <code>IOException</code> will be thrown.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public RrdDb(String path, boolean readOnly) throws IOException {
		this(path, readOnly, null);
	}

	/**
	 * Constructor used to open already existing RRD backed with a storage
	 * (backend) different from default. Constructor obtains read or read/write
	 * access to this RRD.
	 *
	 * @param path
	 *            Path to existing RRD.
	 * @param readOnly
	 *            Should be set to <code>false</code> if you want to update the
	 *            underlying RRD. If you want just to fetch data from the RRD
	 *            file (read-only access), specify <code>true</code>. If you try
	 *            to update RRD file open in read-only mode (
	 *            <code>readOnly</code> set to <code>true</code>),
	 *            <code>IOException</code> will be thrown.
	 * @param factory
	 *            Backend factory which will be used for this RRD.
	 * @throws FileNotFoundException
	 *             Thrown if the requested file does not exist.
	 * @throws java.io.IOException
	 *             Thrown in case of general I/O error (bad RRD file, for
	 *             example).
	 * @see RrdBackendFactory
	 */
	public RrdDb(String path, boolean readOnly, RrdBackendFactory factory) throws IOException {
		this(path, null, readOnly, factory);
	}

	/**
	 * <p>
	 * Constructor used to open already existing RRD. The URI will checked
	 * against the active factories. If it's a relative URI (no scheme given, or
	 * just a plain path), the default factory will be used.
	 * </p>
	 * <p>
	 * Constructor obtains read or read/write access to this RRD.
	 * </p>
	 *
	 * @param path
	 *            Path to existing RRD.
	 * @param readOnly
	 *            Should be set to <code>false</code> if you want to update the
	 *            underlying RRD. If you want just to fetch data from the RRD
	 *            file (read-only access), specify <code>true</code>. If you try
	 *            to update RRD file open in read-only mode (
	 *            <code>readOnly</code> set to <code>true</code>),
	 *            <code>IOException</code> will be thrown.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public RrdDb(URI path, boolean readOnly) throws IOException {
		this(null, path, readOnly, null);
	}

	/**
	 * <p>
	 * Constructor used to open already existing RRD. The path will be parsed as
	 * an URI and checked against the active factories. If it's a relative URI
	 * (no scheme given, or just a plain path), the default factory will be
	 * used.
	 * </p>
	 * <p>
	 * Constructor obtains read/write access to this RRD.
	 * </p>
	 *
	 * @param path
	 *            Path to existing RRD.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public RrdDb(String path) throws IOException {
		this(path, false, null);
	}

	/**
	 * <p>
	 * Constructor used to open already existing RRD. The URI will checked
	 * against the active factories. If it's a relative URI (no scheme given, or
	 * just a plain path), the default factory will be used.
	 * </p>
	 * <p>
	 * Constructor obtains read/write access to this RRD.
	 * </p>
	 *
	 * @param path
	 *            Path to existing RRD.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public RrdDb(URI path) throws IOException {
		this(null, path, false, null);
	}

	/**
	 * Constructor used to open already existing RRD in R/W mode with a storage
	 * (backend) type different from default.
	 *
	 * @param path
	 *            Path to existing RRD.
	 * @param factory
	 *            Backend factory used to create this RRD.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 * @see RrdBackendFactory
	 */
	public RrdDb(String path, RrdBackendFactory factory) throws IOException {
		this(path, null, false, factory);
	}

	private RrdDb(String rrdPath, URI rrdUri, boolean readOnly, RrdBackendFactory factory) throws IOException {

		rrdUri = buildUri(rrdPath, rrdUri, factory);
		factory = checkFactory(rrdUri, factory);

		// opens existing RRD file - throw exception if the file does not
		// exist...
		if (!factory.exists(rrdUri)) {
			throw new FileNotFoundException("Could not open " + rrdUri + " [non existent]");
		}
		backend = factory.open(rrdUri, readOnly);
		backend.setFactory(factory);
		try {
			// restore header
			header = new Header(this, (RrdDef) null);

			if (factory.shouldValidateHeader(rrdUri)) {
				header.validateHeader();
			}

			// restore datasources
			int dsCount = header.getDsCount();
			datasources = new Datasource[dsCount];
			for (int i = 0; i < dsCount; i++) {
				datasources[i] = new Datasource(this, null);
			}
			// restore archives
			int arcCount = header.getArcCount();
			archives = new Archive[arcCount];
			for (int i = 0; i < arcCount; i++) {
				archives[i] = new Archive(this, null);
			}
		} catch (IOException e) {
			backend.close();
			throw e;
		}
	}

	/**
	 * <p>
	 * Constructor used to create RRD files from external file sources.
	 * Supported external file sources are:
	 * </p>
	 * <ul>
	 * <li>RRDTool/Rrd4j XML file dumps (i.e files created with
	 * <code>rrdtool dump</code> command).
	 * <li>RRDTool binary files.
	 * </ul>
	 * <p>
	 * The path for the new rrd will be parsed as an URI and checked against the
	 * active factories. If it's a relative URI (no scheme given, or just a
	 * plain path), the default factory will be used.
	 * </p>
	 * <p>
	 * Rrd4j and RRDTool use the same format for XML dump and this constructor
	 * should be used to (re)create Rrd4j RRD files from XML dumps. First, dump
	 * the content of a RRDTool RRD file (use command line):
	 * </p>
	 * 
	 * <pre>
	 * rrdtool dump original.rrd &gt; original.xml
	 * </pre>
	 * <p>
	 * Than, use the file <code>original.xml</code> to create Rrd4j RRD file
	 * named <code>copy.rrd</code>:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;original.xml&quot;);
	 * </pre>
	 * <p>
	 * or:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;xml:/original.xml&quot;);
	 * </pre>
	 * <p>
	 * To read RRDTool files directly, specify <code>rrdtool:/</code> prefix in
	 * the <code>externalPath</code> argument. For example, to create Rrd4j
	 * compatible file named <code>copy.rrd</code> from the file
	 * <code>original.rrd</code> created with RRDTool, use the following code:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;rrdtool:/original.rrd&quot;);
	 * </pre>
	 * <p>
	 * Note that the prefix <code>xml:/</code> or <code>rrdtool:/</code> is
	 * necessary to distinguish between XML and RRDTool's binary sources. If no
	 * prefix is supplied, XML format is assumed.
	 * </p>
	 *
	 * @param rrdPath
	 *            Path to a RRD file which will be created
	 * @param externalPath
	 *            Path to an external file which should be imported, with an
	 *            optional <code>xml:/</code> or <code>rrdtool:/</code> prefix.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public RrdDb(String rrdPath, String externalPath) throws IOException {
		this(rrdPath, null, externalPath, null);
	}

	/**
	 * <p>
	 * Constructor used to create RRD files from external file sources.
	 * Supported external file sources are:
	 * </p>
	 * <ul>
	 * <li>RRDTool/Rrd4j XML file dumps (i.e files created with
	 * <code>rrdtool dump</code> command).
	 * <li>RRDTool binary files.
	 * </ul>
	 * <p>
	 * The path for the new rrd will be parsed as an URI and checked against the
	 * active factories. If it's a relative URI (no scheme given, or just a
	 * plain path), the default factory will be used.
	 * </p>
	 * <p>
	 * Rrd4j and RRDTool use the same format for XML dump and this constructor
	 * should be used to (re)create Rrd4j RRD files from XML dumps. First, dump
	 * the content of a RRDTool RRD file (use command line):
	 * </p>
	 * 
	 * <pre>
	 * rrdtool dump original.rrd &gt; original.xml
	 * </pre>
	 * <p>
	 * Than, use the file <code>original.xml</code> to create Rrd4j RRD file
	 * named <code>copy.rrd</code>:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;original.xml&quot;);
	 * </pre>
	 * <p>
	 * or:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;xml:/original.xml&quot;);
	 * </pre>
	 * <p>
	 * To read RRDTool files directly, specify <code>rrdtool:/</code> prefix in
	 * the <code>externalPath</code> argument. For example, to create Rrd4j
	 * compatible file named <code>copy.rrd</code> from the file
	 * <code>original.rrd</code> created with RRDTool, use the following code:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;rrdtool:/original.rrd&quot;);
	 * </pre>
	 * <p>
	 * Note that the prefix <code>xml:/</code> or <code>rrdtool:/</code> is
	 * necessary to distinguish between XML and RRDTool's binary sources. If no
	 * prefix is supplied, XML format is assumed.
	 * </p>
	 *
	 * @param rrdPath
	 *            Path to a RRD file which will be created
	 * @param externalPath
	 *            Path to an external file which should be imported, with an
	 *            optional <code>xml:/</code> or <code>rrdtool:/</code> prefix.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public RrdDb(URI rrdPath, String externalPath) throws IOException {
		this(null, rrdPath, externalPath, null);
	}

	/**
	 * <p>
	 * Constructor used to create RRD files from external file sources with a
	 * backend type different from default. Supported external file sources are:
	 * </p>
	 * <ul>
	 * <li>RRDTool/Rrd4j XML file dumps (i.e files created with
	 * <code>rrdtool dump</code> command).
	 * <li>RRDTool binary files.
	 * </ul>
	 * <p>
	 * Rrd4j and RRDTool use the same format for XML dump and this constructor
	 * should be used to (re)create Rrd4j RRD files from XML dumps. First, dump
	 * the content of a RRDTool RRD file (use command line):
	 * </p>
	 * 
	 * <pre>
	 * rrdtool dump original.rrd &gt; original.xml
	 * </pre>
	 * <p>
	 * Than, use the file <code>original.xml</code> to create Rrd4j RRD file
	 * named <code>copy.rrd</code>:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;original.xml&quot;);
	 * </pre>
	 * <p>
	 * or:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;xml:/original.xml&quot;);
	 * </pre>
	 * <p>
	 * To read RRDTool files directly, specify <code>rrdtool:/</code> prefix in
	 * the <code>externalPath</code> argument. For example, to create Rrd4j
	 * compatible file named <code>copy.rrd</code> from the file
	 * <code>original.rrd</code> created with RRDTool, use the following code:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd = new RrdDb(&quot;copy.rrd&quot;, &quot;rrdtool:/original.rrd&quot;);
	 * </pre>
	 * <p>
	 * Note that the prefix <code>xml:/</code> or <code>rrdtool:/</code> is
	 * necessary to distinguish between XML and RRDTool's binary sources. If no
	 * prefix is supplied, XML format is assumed.
	 * </p>
	 *
	 * @param rrdPath
	 *            Path to RRD which will be created
	 * @param externalPath
	 *            Path to an external file which should be imported, with an
	 *            optional <code>xml:/</code> or <code>rrdtool:/</code> prefix.
	 * @param factory
	 *            Backend factory which will be used to create storage (backend)
	 *            for this RRD.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 * @see RrdBackendFactory
	 */
	public RrdDb(String rrdPath, String externalPath, RrdBackendFactory factory) throws IOException {
		this(rrdPath, null, externalPath, factory);
	}

	private RrdDb(String rrdPath, URI rrdUri, String externalPath, RrdBackendFactory factory) throws IOException {

		rrdUri = buildUri(rrdPath, rrdUri, factory);
		factory = checkFactory(rrdUri, factory);

		DataImporter reader;
		if (externalPath.startsWith(PREFIX_RRDTool)) {
			String rrdToolPath = externalPath.substring(PREFIX_RRDTool.length());
			reader = new RrdToolReader(rrdToolPath);
		} else {
			throw new IllegalArgumentException("unsupported prefix: " + externalPath);
		}
		backend = factory.open(rrdUri, false);
		backend.setFactory(factory);
		try {
			backend.setLength(reader.getEstimatedSize());
			// create header
			header = new Header(this, reader);
			// create datasources
			datasources = new Datasource[reader.getDsCount()];
			for (int i = 0; i < datasources.length; i++) {
				datasources[i] = new Datasource(this, reader, i);
			}
			// create archives
			archives = new Archive[reader.getArcCount()];
			for (int i = 0; i < archives.length; i++) {
				archives[i] = new Archive(this, reader, i);
			}
			reader.release();

			// XMLReader is a rather huge DOM tree, release memory ASAP
			reader = null;
		} catch (IOException e) {
			backend.close();
			throw e;
		}
	}

	private RrdBackendFactory checkFactory(URI uri, RrdBackendFactory factory) {
		if (factory == null) {
			return RrdBackendFactory.findFactory(uri);
		} else {
			return factory;
		}
	}

	private URI buildUri(String rrdPath, URI rrdUri, RrdBackendFactory factory) {
		if (rrdUri != null) {
			return rrdUri;
		} else if (factory == null) {
			return RrdBackendFactory.buildGenericUri(rrdPath);
		} else {
			return factory.getUri(rrdPath);
		}
	}

	/**
	 * Closes RRD. No further operations are allowed on this RrdDb object.
	 *
	 * @throws java.io.IOException
	 *             Thrown in case of I/O related error.
	 */
	public synchronized void close() throws IOException {
		if (!closed) {
			closed = true;
			backend.close();
		}
	}

	/**
	 * Returns true if the RRD is closed.
	 *
	 * @return true if closed, false otherwise
	 */
	public boolean isClosed() {
		return closed;
	}

	/**
	 * Returns RRD header.
	 *
	 * @return Header object
	 */
	public Header getHeader() {
		return header;
	}

	/**
	 * Returns Datasource object for the given datasource index.
	 *
	 * @param dsIndex
	 *            Datasource index (zero based)
	 * @return Datasource object
	 */
	public Datasource getDatasource(int dsIndex) {
		return datasources[dsIndex];
	}

	/**
	 * Returns Archive object for the given archive index.
	 *
	 * @param arcIndex
	 *            Archive index (zero based)
	 * @return Archive object
	 */
	public Archive getArchive(int arcIndex) {
		return archives[arcIndex];
	}

	/**
	 * Returns an array of datasource names defined in RRD.
	 *
	 * @return Array of datasource names.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public String[] getDsNames() throws IOException {
		int n = datasources.length;
		String[] dsNames = new String[n];
		for (int i = 0; i < n; i++) {
			dsNames[i] = datasources[i].getName();
		}
		return dsNames;
	}

	/**
	 * <p>
	 * Creates new sample with the given timestamp and all datasource values set
	 * to 'unknown'. Use returned <code>Sample</code> object to specify
	 * datasource values for the given timestamp. See documentation for
	 * {@link Sample Sample} for an explanation how to do this.
	 * </p>
	 * <p>
	 * Once populated with data source values, call Sample's
	 * {@link com.aerse.core.Sample#update() update()} method to actually store
	 * sample in the RRD associated with it.
	 * </p>
	 *
	 * @param time
	 *            Sample timestamp rounded to the nearest second (without
	 *            milliseconds).
	 * @return Fresh sample with the given timestamp and all data source values
	 *         set to 'unknown'.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public Sample createSample(long time) throws IOException {
		return new Sample(this, time);
	}

	/**
	 * <p>
	 * Creates new sample with the current timestamp and all data source values
	 * set to 'unknown'. Use returned <code>Sample</code> object to specify
	 * datasource values for the current timestamp. See documentation for
	 * {@link Sample Sample} for an explanation how to do this.
	 * </p>
	 * <p>
	 * Once populated with data source values, call Sample's
	 * {@link com.aerse.core.Sample#update() update()} method to actually store
	 * sample in the RRD associated with it.
	 * </p>
	 *
	 * @return Fresh sample with the current timestamp and all data source
	 *         values set to 'unknown'.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public Sample createSample() throws IOException {
		return createSample(Util.getTime());
	}

	/**
	 * Prepares fetch request to be executed on this RRD. Use returned
	 * <code>FetchRequest</code> object and its
	 * {@link com.aerse.core.FetchRequest#fetchData() fetchData()} method to
	 * actually fetch data from the RRD file.
	 *
	 * @param consolFun
	 *            Consolidation function to be used in fetch request.
	 * @param fetchStart
	 *            Starting timestamp for fetch request.
	 * @param fetchEnd
	 *            Ending timestamp for fetch request.
	 * @param resolution
	 *            Fetch resolution (see RRDTool's <a
	 *            href="http://oss.oetiker.ch/rrdtool/doc/rrdfetch.en.html"
	 *            target="man">rrdfetch man page</a> for an explanation of this
	 *            parameter.
	 * @return Request object that should be used to actually fetch data from
	 *         RRD
	 */
	public FetchRequest createFetchRequest(ConsolFun consolFun, long fetchStart, long fetchEnd, long resolution) {
		return new FetchRequest(this, consolFun, fetchStart, fetchEnd, resolution);
	}

	/**
	 * Prepares fetch request to be executed on this RRD. Use returned
	 * <code>FetchRequest</code> object and its
	 * {@link com.aerse.core.FetchRequest#fetchData() fetchData()} method to
	 * actually fetch data from this RRD. Data will be fetched with the smallest
	 * possible resolution (see RRDTool's <a
	 * href="http://oss.oetiker.ch/rrdtool/doc/rrdfetch.en.html"
	 * target="man">rrdfetch man page</a> for the explanation of the resolution
	 * parameter).
	 *
	 * @param consolFun
	 *            Consolidation function to be used in fetch request.
	 * @param fetchStart
	 *            Starting timestamp for fetch request.
	 * @param fetchEnd
	 *            Ending timestamp for fetch request.
	 * @return Request object that should be used to actually fetch data from
	 *         RRD.
	 */
	public FetchRequest createFetchRequest(ConsolFun consolFun, long fetchStart, long fetchEnd) {
		return createFetchRequest(consolFun, fetchStart, fetchEnd, 1);
	}

	final synchronized void store(Sample sample) throws IOException {
		if (closed) {
			throw new IllegalStateException("RRD already closed, cannot store this sample");
		}
		long newTime = sample.getTime();
		long lastTime = header.getLastUpdateTime();
		if (lastTime >= newTime) {
			throw new IllegalArgumentException("Bad sample time: " + newTime + ". Last update time was " + lastTime + ", at least one second step is required");
		}
		double[] newValues = sample.getValues();
		for (int i = 0; i < datasources.length; i++) {
			double newValue = newValues[i];
			datasources[i].process(newTime, newValue);
		}
		header.setLastUpdateTime(newTime);
	}

	synchronized FetchData fetchData(FetchRequest request) throws IOException {
		if (closed) {
			throw new IllegalStateException("RRD already closed, cannot fetch data");
		}
		Archive archive = findMatchingArchive(request);
		return archive.fetchData(request);
	}

	/**
	 * findMatchingArchive.
	 *
	 * @param request
	 *            a {@link com.aerse.core.FetchRequest} object.
	 * @return a {@link com.aerse.core.Archive} object.
	 * @throws java.io.IOException
	 *             if any.
	 */
	public Archive findMatchingArchive(FetchRequest request) throws IOException {
		ConsolFun consolFun = request.getConsolFun();
		long fetchStart = request.getFetchStart();
		long fetchEnd = request.getFetchEnd();
		long resolution = request.getResolution();
		Archive bestFullMatch = null;
		Archive bestPartialMatch = null;
		long bestStepDiff = 0;
		long bestMatch = 0;
		for (Archive archive : archives) {
			if (archive.getConsolFun() == consolFun) {
				long arcStep = archive.getArcStep();
				long arcStart = archive.getStartTime() - arcStep;
				long fullMatch = fetchEnd - fetchStart;
				// we need step difference in either full or partial case
				long tmpStepDiff = Math.abs(archive.getArcStep() - resolution);
				if (arcStart <= fetchStart) {
					// best full match
					if (bestFullMatch == null || tmpStepDiff < bestStepDiff) {
						bestStepDiff = tmpStepDiff;
						bestFullMatch = archive;
					}
				} else {
					// best partial match
					long tmpMatch = fullMatch;
					if (arcStart > fetchStart) {
						tmpMatch -= (arcStart - fetchStart);
					}
					if (bestPartialMatch == null || bestMatch < tmpMatch || (bestMatch == tmpMatch && tmpStepDiff < bestStepDiff)) {
						bestPartialMatch = archive;
						bestMatch = tmpMatch;
					}
				}
			}
		}
		if (bestFullMatch != null) {
			return bestFullMatch;
		} else if (bestPartialMatch != null) {
			return bestPartialMatch;
		} else {
			throw new IllegalStateException("RRD file does not contain RRA: " + consolFun + " archive");
		}
	}

	/**
	 * Finds the archive that best matches to the start time (time period being
	 * start-time until now) and requested resolution.
	 *
	 * @param consolFun
	 *            Consolidation function of the datasource.
	 * @param startTime
	 *            Start time of the time period in seconds.
	 * @param resolution
	 *            Requested fetch resolution.
	 * @return Reference to the best matching archive.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O related error.
	 */
	public Archive findStartMatchArchive(String consolFun, long startTime, long resolution) throws IOException {
		long arcStep, diff;
		int fallBackIndex = 0;
		int arcIndex = -1;
		long minDiff = Long.MAX_VALUE;
		long fallBackDiff = Long.MAX_VALUE;

		for (int i = 0; i < archives.length; i++) {
			if (archives[i].getConsolFun().toString().equals(consolFun)) {
				arcStep = archives[i].getArcStep();
				diff = Math.abs(resolution - arcStep);

				// Now compare start time, see if this archive encompasses the
				// requested interval
				if (startTime >= archives[i].getStartTime()) {
					if (diff == 0) // Best possible match either way
					{
						return archives[i];
					} else if (diff < minDiff) {
						minDiff = diff;
						arcIndex = i;
					}
				} else if (diff < fallBackDiff) {
					fallBackDiff = diff;
					fallBackIndex = i;
				}
			}
		}

		return (arcIndex >= 0 ? archives[arcIndex] : archives[fallBackIndex]);
	}

	/**
	 * Returns string representing complete internal RRD state. The returned
	 * string can be printed to <code>stdout</code> and/or used for debugging
	 * purposes.
	 *
	 * @return String representing internal RRD state.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O related error.
	 */
	public synchronized String dump() throws IOException {
		StringBuilder buffer = new StringBuilder();
		buffer.append(header.dump());
		for (Datasource datasource : datasources) {
			buffer.append(datasource.dump());
		}
		for (Archive archive : archives) {
			buffer.append(archive.dump());
		}
		return buffer.toString();
	}

	final void archive(Datasource datasource, double value, long numUpdates) throws IOException {
		int dsIndex = getDsIndex(datasource.getName());
		for (Archive archive : archives) {
			archive.archive(dsIndex, value, numUpdates);
		}
	}

	/**
	 * Returns internal index number for the given datasource name.
	 *
	 * @param dsName
	 *            Data source name.
	 * @return Internal index of the given data source name in this RRD.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public int getDsIndex(String dsName) throws IOException {
		for (int i = 0; i < datasources.length; i++) {
			if (datasources[i].getName().equals(dsName)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unknown datasource name: " + dsName);
	}

	/**
	 * Checks presence of a specific datasource.
	 *
	 * @param dsName
	 *            Datasource name to check
	 * @return <code>true</code> if datasource is present in this RRD,
	 *         <code>false</code> otherwise
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error.
	 */
	public boolean containsDs(String dsName) throws IOException {
		for (Datasource datasource : datasources) {
			if (datasource.getName().equals(dsName)) {
				return true;
			}
		}
		return false;
	}

	Datasource[] getDatasources() {
		return datasources;
	}

	Archive[] getArchives() {
		return archives;
	}

	/**
	 * Returns time of last update operation as timestamp (in seconds).
	 *
	 * @return Last update time (in seconds).
	 * @throws java.io.IOException
	 *             if any.
	 */
	public synchronized long getLastUpdateTime() throws IOException {
		return header.getLastUpdateTime();
	}

	/**
	 * <p>
	 * Returns RRD definition object which can be used to create new RRD with
	 * the same creation parameters but with no data in it.
	 * </p>
	 * <p>
	 * Example:
	 * </p>
	 * 
	 * <pre>
	 * RrdDb rrd1 = new RrdDb(&quot;original.rrd&quot;);
	 * RrdDef def = rrd1.getRrdDef();
	 * // fix path
	 * def.setPath(&quot;empty_copy.rrd&quot;);
	 * // create new RRD file
	 * RrdDb rrd2 = new RrdDb(def);
	 * </pre>
	 *
	 * @return RRD definition.
	 * @throws java.io.IOException
	 *             if any.
	 */
	public synchronized RrdDef getRrdDef() throws IOException {
		// set header
		long startTime = header.getLastUpdateTime();
		long step = header.getStep();
		int version = header.getVersion();
		String path = backend.getPath();
		RrdDef rrdDef = new RrdDef(path, startTime, step, version);
		// add datasources
		for (Datasource datasource : datasources) {
			DsDef dsDef = new DsDef(datasource.getName(), datasource.getType(), datasource.getHeartbeat(), datasource.getMinValue(), datasource.getMaxValue());
			rrdDef.addDatasource(dsDef);
		}
		// add archives
		for (Archive archive : archives) {
			ArcDef arcDef = new ArcDef(archive.getConsolFun(), archive.getXff(), archive.getSteps(), archive.getRows());
			rrdDef.addArchive(arcDef);
		}
		return rrdDef;
	}

	/**
	 * finalize.
	 *
	 * @throws java.lang.Throwable
	 *             if any.
	 */
	protected void finalize() throws Throwable {
		try {
			close();
		} finally {
			super.finalize();
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * Copies object's internal state to another RrdDb object.
	 */
	public synchronized void copyStateTo(RrdUpdater other) throws IOException {
		if (!(other instanceof RrdDb)) {
			throw new IllegalArgumentException("Cannot copy RrdDb object to " + other.getClass().getName());
		}
		RrdDb otherRrd = (RrdDb) other;
		header.copyStateTo(otherRrd.header);
		for (int i = 0; i < datasources.length; i++) {
			int j = Util.getMatchingDatasourceIndex(this, i, otherRrd);
			if (j >= 0) {
				datasources[i].copyStateTo(otherRrd.datasources[j]);
			}
		}
		for (int i = 0; i < archives.length; i++) {
			int j = Util.getMatchingArchiveIndex(this, i, otherRrd);
			if (j >= 0) {
				archives[i].copyStateTo(otherRrd.archives[j]);
			}
		}
	}

	/**
	 * Returns Datasource object corresponding to the given datasource name.
	 *
	 * @param dsName
	 *            Datasource name
	 * @return Datasource object corresponding to the give datasource name or
	 *         null if not found.
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public Datasource getDatasource(String dsName) throws IOException {
		try {
			return getDatasource(getDsIndex(dsName));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Returns index of Archive object with the given consolidation function and
	 * the number of steps. Exception is thrown if such archive could not be
	 * found.
	 *
	 * @param consolFun
	 *            Consolidation function
	 * @param steps
	 *            Number of archive steps
	 * @return Requested Archive object
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public int getArcIndex(ConsolFun consolFun, int steps) throws IOException {
		for (int i = 0; i < archives.length; i++) {
			if (archives[i].getConsolFun() == consolFun && archives[i].getSteps() == steps) {
				return i;
			}
		}
		throw new IllegalArgumentException("Could not find archive " + consolFun + "/" + steps);
	}

	/**
	 * Returns Archive object with the given consolidation function and the
	 * number of steps.
	 *
	 * @param consolFun
	 *            Consolidation function
	 * @param steps
	 *            Number of archive steps
	 * @return Requested Archive object or null if no such archive could be
	 *         found
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public Archive getArchive(ConsolFun consolFun, int steps) throws IOException {
		try {
			return getArchive(getArcIndex(consolFun, steps));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Returns canonical path to the underlying RRD file. Note that this method
	 * makes sense just for ordinary RRD files created on the disk - an
	 * exception will be thrown for RRD objects created in memory or with custom
	 * backends.
	 *
	 * @return Canonical path to RRD file;
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error or if the underlying backend is
	 *             not derived from RrdFileBackend.
	 */
	public String getCanonicalPath() throws IOException {
		if (backend instanceof RrdFileBackend) {
			return ((RrdFileBackend) backend).getCanonicalPath();
		} else {
			throw new IOException("The underlying backend has no canonical path");
		}
	}

	/**
	 * Returns the path to this RRD.
	 *
	 * @return Path to this RRD.
	 */
	public String getPath() {
		return backend.getPath();
	}

	/**
	 * Returns the URI to this RRD, as seen by the backend.
	 *
	 * @return URI to this RRD.
	 */
	public URI getUri() {
		return backend.getUri();
	}

	/**
	 * Returns backend object for this RRD which performs actual I/O operations.
	 *
	 * @return RRD backend for this RRD.
	 */
	public RrdBackend getRrdBackend() {
		return backend;
	}

	/**
	 * Required to implement RrdUpdater interface. You should never call this
	 * method directly.
	 *
	 * @return Allocator object
	 */
	public RrdAllocator getRrdAllocator() {
		return allocator;
	}

	/**
	 * Returns an array of bytes representing the whole RRD.
	 *
	 * @return All RRD bytes
	 * @throws java.io.IOException
	 *             Thrown in case of I/O related error.
	 */
	public synchronized byte[] getBytes() throws IOException {
		return backend.readAll();
	}

	/**
	 * Sets default backend factory to be used. This method is just an alias for
	 * {@link com.aerse.core.RrdBackendFactory#setDefaultFactory(String)}.
	 *
	 * @param factoryName
	 *            Name of the backend factory to be set as default.
	 * @throws java.lang.IllegalArgumentException
	 *             Thrown if invalid factory name is supplied, or not called
	 *             before the first backend object (before the first RrdDb
	 *             object) is created.
	 */
	public static void setDefaultFactory(String factoryName) {
		RrdBackendFactory.setDefaultFactory(factoryName);
	}

	/**
	 * Returns an array of last datasource values. The first value in the array
	 * corresponds to the first datasource defined in the RrdDb and so on.
	 *
	 * @return Array of last datasource values
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public synchronized double[] getLastDatasourceValues() throws IOException {
		double[] values = new double[datasources.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = datasources[i].getLastValue();
		}
		return values;
	}

	/**
	 * Returns the last stored value for the given datasource.
	 *
	 * @param dsName
	 *            Datasource name
	 * @return Last stored value for the given datasource
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 * @throws java.lang.IllegalArgumentException
	 *             Thrown if no datasource in this RrdDb matches the given
	 *             datasource name
	 */
	public synchronized double getLastDatasourceValue(String dsName) throws IOException {
		int dsIndex = getDsIndex(dsName);
		return datasources[dsIndex].getLastValue();
	}

	/**
	 * Returns the number of datasources defined in the file
	 *
	 * @return The number of datasources defined in the file
	 */
	public int getDsCount() {
		return datasources.length;
	}

	/**
	 * Returns the number of RRA archives defined in the file
	 *
	 * @return The number of RRA archives defined in the file
	 */
	public int getArcCount() {
		return archives.length;
	}

	/**
	 * Returns the last time when some of the archives in this RRD was updated.
	 * This time is not the same as the {@link #getLastUpdateTime()} since RRD
	 * file can be updated without updating any of the archives.
	 *
	 * @return last time when some of the archives in this RRD was updated
	 * @throws java.io.IOException
	 *             Thrown in case of I/O error
	 */
	public long getLastArchiveUpdateTime() throws IOException {
		long last = 0;
		for (Archive archive : archives) {
			last = Math.max(last, archive.getEndTime());
		}
		return last;
	}

	/**
	 * getInfo.
	 *
	 * @return a {@link java.lang.String} object.
	 * @throws java.io.IOException
	 *             if any.
	 */
	public synchronized String getInfo() throws IOException {
		return header.getInfo();
	}

	/**
	 * setInfo.
	 *
	 * @param info
	 *            a {@link java.lang.String} object.
	 * @throws java.io.IOException
	 *             if any.
	 */
	public synchronized void setInfo(String info) throws IOException {
		header.setInfo(info);
	}

	/**
	 * main.
	 *
	 * @param args
	 *            an array of {@link java.lang.String} objects.
	 */
	public static void main(String[] args) {
		System.out.println("RRD4J :: RRDTool choice for the Java world");
		System.out.println("===============================================================================");
		System.out.println("RRD4J base directory: " + Util.getRrd4jHomeDirectory());
		long time = Util.getTime();
		System.out.println("Current time: " + time + ": " + new Date(time * 1000L));
		System.out.println("-------------------------------------------------------------------------------");
		System.out.println("See https://github.com/rrd4j/rrd4j for more information and the latest version.");
		System.out.println("Copyright 2017 The RRD4J Authors. Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor. Copyright (c) 2013 The OpenNMS Group, Inc.. Licensed under the Apache License, Version 2.0.");
	}

}
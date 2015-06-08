/*
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License along
 *   with this program; if not, write to the Free Software Foundation, Inc.,
 *   51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package info.minzhou.perfj;

import com.sun.tools.attach.VirtualMachine;

import java.io.*;
import java.util.Enumeration;
import java.util.Properties;
import java.util.UUID;

public class PerfJ {

  public static final String PERFJ_SYSTEM_PROPERTIES_FILE = "info-minzhou-perfj.properties";
  public static final String KEY_PERFJ_LIB_PATH = "info.minzhou.perfj.lib.path";
  public static final String KEY_PERFJ_LIB_NAME = "info.minzhou.perfj.lib.name";
  public static final String KEY_PERFJ_TEMPDIR = "info.minzhou.perfj.tempdir";
  public static final String KEY_PERFJ_USE_SYSTEMLIB = "info.minzhou.perfj.use.systemlib";


  static {
    loadSystemProperties();
  }

  /**
   * load system properties when configuration file of the name
   * {@link #PERFJ_SYSTEM_PROPERTIES_FILE} is found
   */
  private static void loadSystemProperties() {
    try {
      InputStream is = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(PERFJ_SYSTEM_PROPERTIES_FILE);

      if (is == null) {
        return; // no configuration file is found
      }

      // Load property file
      Properties props = new Properties();
      props.load(is);
      is.close();
      Enumeration<?> names = props.propertyNames();
      while (names.hasMoreElements()) {
        String name = (String) names.nextElement();
        if (name.startsWith("info.minzhou.perfj.")) {
          if (System.getProperty(name) == null) {
            System.setProperty(name, props.getProperty(name));
          }
        }
      }
    } catch (Throwable ex) {
      System.err.println("Could not load '" + PERFJ_SYSTEM_PROPERTIES_FILE + "' from classpath: "
          + ex.toString());
    }
  }



  private static File findNativeLibrary() {
    boolean useSystemLib = Boolean.parseBoolean(System.getProperty(KEY_PERFJ_USE_SYSTEMLIB, "false"));
    if (useSystemLib) {
      return null; // Use a pre-installed libperfj
    }

    // Try to load the library in info.minzhou.perfj.lib.path  */
    String perfjNativeLibraryPath = System.getProperty(KEY_PERFJ_LIB_PATH);
    String perfjNativeLibraryName = System.getProperty(KEY_PERFJ_LIB_NAME);

    // Resolve the library file name with a suffix (e.g., dll, .so, etc.)
    if (perfjNativeLibraryName == null) {
      perfjNativeLibraryName = System.mapLibraryName("perfj");
    }

    if (perfjNativeLibraryPath != null) {
      File nativeLib = new File(perfjNativeLibraryPath, perfjNativeLibraryName);
      if (nativeLib.exists()) {
        return nativeLib;
      }
    }

    // Load native library inside a jar file
    perfjNativeLibraryPath = "/info/minzhou/perfj/native";
    boolean hasNativeLib = hasResource(perfjNativeLibraryPath + "/" + perfjNativeLibraryName);

    if (!hasNativeLib) {
      throw new RuntimeException("no native library is found for perfj");
    }

    // Temporary folder for the native lib. Use the value of info.minzhou.perfj.tempdir or java.io.tmpdir
    File tempFolder = new File(System.getProperty(KEY_PERFJ_TEMPDIR, System.getProperty("java.io.tmpdir")));
    if (!tempFolder.exists()) {
      boolean created = tempFolder.mkdirs();
      if (!created) {
        // if created == false, it will fail eventually in the later part
      }
    }

    // Extract and load a native library inside the jar file
    return extractLibraryFile(perfjNativeLibraryPath, perfjNativeLibraryName, tempFolder.getAbsolutePath());
  }

  private static boolean hasResource(String path) {
    return PerfJ.class.getResource(path) != null;
  }


  /**
   * Extract the specified library file to the target folder
   *
   * @param libraryFolder
   * @param libraryFileName
   * @param targetFolder
   * @return
   */
  private static File extractLibraryFile(String libraryFolder, String libraryFileName, String targetFolder) {
    String nativeLibraryFilePath = libraryFolder + "/" + libraryFileName;

    // Attach UUID to the native library file to ensure multiple processes can read the libperfj multiple times.
    String uuid = UUID.randomUUID().toString();
    String extractedLibFileName = String.format("perfj-%s-%s", uuid, libraryFileName);
    File extractedLibFile = new File(targetFolder, extractedLibFileName);

    try {
      // Extract a native library file into the target directory
      InputStream reader = null;
      FileOutputStream writer = null;
      try {
        reader = PerfJ.class.getResourceAsStream(nativeLibraryFilePath);
        try {
          writer = new FileOutputStream(extractedLibFile);

          byte[] buffer = new byte[8192];
          int bytesRead = 0;
          while ((bytesRead = reader.read(buffer)) != -1) {
            writer.write(buffer, 0, bytesRead);
          }
        } finally {
          if (writer != null) {
            writer.close();
          }
        }
      } finally {
        if (reader != null) {
          reader.close();
        }

        // Delete the extracted lib file on JVM exit.
        extractedLibFile.deleteOnExit();
      }

      // Set executable (x) flag to enable Java to load the native library
      boolean success = extractedLibFile.setReadable(true) &&
          extractedLibFile.setWritable(true, true) &&
          extractedLibFile.setExecutable(true);
      if (!success) {
        // Setting file flag may fail, but in this case another error will be thrown in later phase
      }

      // Check whether the contents are properly copied from the resource folder
      {
        InputStream nativeIn = null;
        InputStream extractedLibIn = null;
        try {
          nativeIn = PerfJ.class.getResourceAsStream(nativeLibraryFilePath);
          extractedLibIn = new FileInputStream(extractedLibFile);

          if (!contentsEquals(nativeIn, extractedLibIn)) {
            throw new RuntimeException(String.format("Failed to write a native library file at %s", extractedLibFile));
          }
        } finally {
          if (nativeIn != null) {
            nativeIn.close();
          }
          if (extractedLibIn != null) {
            extractedLibIn.close();
          }
        }
      }

      return new File(targetFolder, extractedLibFileName);
    } catch (IOException e) {
      e.printStackTrace(System.err);
      return null;
    }
  }

  private static boolean contentsEquals(InputStream in1, InputStream in2)
      throws IOException {
    if (!(in1 instanceof BufferedInputStream)) {
      in1 = new BufferedInputStream(in1);
    }
    if (!(in2 instanceof BufferedInputStream)) {
      in2 = new BufferedInputStream(in2);
    }

    int ch = in1.read();
    while (ch != -1) {
      int ch2 = in2.read();
      if (ch != ch2) {
        return false;
      }
      ch = in1.read();
    }
    int ch2 = in2.read();
    return ch2 == -1;
  }

  private static void loadAgent(String pid, String options) throws Exception {
    VirtualMachine vm = VirtualMachine.attach(pid);
    try {
      vm.loadAgentPath(findNativeLibrary().getAbsolutePath(), options);
    } catch (com.sun.tools.attach.AgentInitializationException e) {
      // rethrow all but the expected exception
      if (!e.getMessage().equals("Agent_OnAttach failed")) throw e;
    } finally {
      vm.detach();
    }
  }

  public static void main(String[] args) throws Exception {
    String pid = args[0];
    String options = "";
    if (args.length > 1) options = args[1];
    loadAgent(pid, options);
  }


}


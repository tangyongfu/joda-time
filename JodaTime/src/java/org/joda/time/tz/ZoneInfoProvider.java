/*
 * Joda Software License, Version 1.0
 *
 *
 * Copyright (c) 2001-2004 Stephen Colebourne.  
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer. 
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:  
 *       "This product includes software developed by the
 *        Joda project (http://www.joda.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The name "Joda" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For
 *    written permission, please contact licence@joda.org.
 *
 * 5. Products derived from this software may not be called "Joda",
 *    nor may "Joda" appear in their name, without prior written
 *    permission of the Joda project.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE JODA AUTHORS OR THE PROJECT
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Joda project and was originally 
 * created by Stephen Colebourne <scolebourne@joda.org>. For more
 * information on the Joda project, please see <http://www.joda.org/>.
 */
package org.joda.time.tz;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.joda.time.DateTimeZone;

/**
 * ZoneInfoProvider loads compiled data files as generated by
 * {@link ZoneInfoCompiler}.
 * <p>
 * ZoneInfoProvider is thread-safe and publicly immutable.
 *
 * @author Brian S O'Neill
 */
public class ZoneInfoProvider implements Provider {
    private static Map loadZoneInfoMap(InputStream in) throws IOException {
        Map map = new TreeMap(String.CASE_INSENSITIVE_ORDER);
        DataInputStream din = new DataInputStream(in);
        try {
            ZoneInfoCompiler.readZoneInfoMap(din, map);
        } finally {
            try {
                din.close();
            } catch (IOException e) {
            }
        }
        map.put("UTC", new SoftReference(DateTimeZone.UTC));
        return map;
    }

    private final File iFileDir;
    private final String iResourcePath;
    private final ClassLoader iLoader;

    // Maps ids to strings or SoftReferences to DateTimeZones.
    private final Map iZoneInfoMap;

    /**
     * ZoneInfoProvider searches the given directory for compiled data files.
     *
     * @throws IOException if directory or map file cannot be read
     */
    public ZoneInfoProvider(File fileDir) throws IOException {
        if (fileDir == null) {
            throw new IllegalArgumentException("No file directory provided");
        }
        if (!fileDir.exists()) {
            throw new IOException("File directory doesn't exist: " + fileDir);
        }
        if (!fileDir.isDirectory()) {
            throw new IOException("File doesn't refer to a directory: " + fileDir);
        }

        iFileDir = fileDir;
        iResourcePath = null;
        iLoader = null;

        iZoneInfoMap = loadZoneInfoMap(openResource("ZoneInfoMap"));
    }

    /**
     * ZoneInfoProvider searches the given ClassLoader resource path for
     * compiled data files. Resources are loaded from the ClassLoader that
     * loaded this class.
     *
     * @throws IOException if directory or map file cannot be read
     */
    public ZoneInfoProvider(String resourcePath) throws IOException {
        this(resourcePath, null, false);
    }

    /**
     * ZoneInfoProvider searches the given ClassLoader resource path for
     * compiled data files.
     *
     * @param loader ClassLoader to load compiled data files from. If null,
     * use system ClassLoader.
     * @throws IOException if directory or map file cannot be read
     */
    public ZoneInfoProvider(String resourcePath, ClassLoader loader)
        throws IOException
    {
        this(resourcePath, loader, true);
    }

    /**
     * @param favorSystemLoader when true, use the system class loader if
     * loader null. When false, use the current class loader if loader is null.
     */
    private ZoneInfoProvider(String resourcePath,
                             ClassLoader loader, boolean favorSystemLoader) 
        throws IOException
    {
        if (resourcePath == null) {
            throw new IllegalArgumentException("No resource path provided");
        }
        if (!resourcePath.endsWith("/")) {
            resourcePath += '/';
        }

        iFileDir = null;
        iResourcePath = resourcePath;

        if (loader == null && !favorSystemLoader) {
            loader = getClass().getClassLoader();
        }

        iLoader = loader;

        iZoneInfoMap = loadZoneInfoMap(openResource("ZoneInfoMap"));
    }

    /**
     * If an error is thrown while loading zone data, uncaughtException is
     * called to log the error and null is returned for this and all future
     * requests.
     */
    public synchronized DateTimeZone getZone(String id) {
        if (id == null) {
            return null;
        }

        Object obj = iZoneInfoMap.get(id);
        if (obj == null) {
            return null;
        }

        if (id.equals(obj)) {
            // Load zone data for the first time.
            return loadZoneData(id);
        }

        if (obj instanceof SoftReference) {
            DateTimeZone tz = (DateTimeZone)((SoftReference)obj).get();
            if (tz != null) {
                return tz;
            }
            // Reference cleared; load data again.
            return loadZoneData(id);
        }

        // If this point is reached, mapping must link to another.
        return getZone((String)obj);
    }

    public synchronized Set getAvailableIDs() {
        return Collections.unmodifiableSet(iZoneInfoMap.keySet());
    }

    /**
     * Called if an exception is thrown from getZone while loading zone
     * data.
     */
    protected void uncaughtException(Exception e) {
        Thread t = Thread.currentThread();
        t.getThreadGroup().uncaughtException(t, e);
    }

    private InputStream openResource(String name) throws IOException {
        InputStream in;
        if (iFileDir != null) {
            in = new FileInputStream(new File(iFileDir, name));
        } else {
            String path = iResourcePath.concat(name);
            if (iLoader != null) {
                in = iLoader.getResourceAsStream(path);
            } else {
                in = ClassLoader.getSystemResourceAsStream(path);
            }
            if (in == null) {
                StringBuffer buf = new StringBuffer(40);
                buf.append("Resource not found: \"");
                buf.append(path);
                buf.append("\" ClassLoader: ");
                buf.append(iLoader != null ? iLoader.toString() : "system");
                throw new IOException(buf.toString());
            }
        }
        return in;
    }

    private DateTimeZone loadZoneData(String id) {
        InputStream in = null;
        try {
            in = openResource(id);
            DateTimeZone tz = DateTimeZoneBuilder.readFrom(in, id);
            iZoneInfoMap.put(id, new SoftReference(tz));
            return tz;
        } catch (IOException e) {
            uncaughtException(e);
            iZoneInfoMap.remove(id);
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
            }
        }
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.script;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.chemistry.opencmis.client.SessionParameterMap;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.codehaus.groovy.control.CompilationFailedException;

/**
 * Command line tool that creates a session and executes a Groovy script.
 */
public class ScriptExecutor {

    public static void main(String[] args) {
        boolean verbose = false;

        try {
            // check arguments
            int parameterParametersFile = 0;
            int parameterScriptFile = 1;
            boolean readUser = false;
            boolean readPassword = false;

            if (args.length > 0) {
                int i = 0;
                while (args[i].startsWith("-")) {
                    if (args[i].equals("-p")) {
                        readPassword = true;
                    } else if (args[i].equals("-u")) {
                        readUser = true;
                    } else if (args[i].equals("-v")) {
                        verbose = true;
                    } else {

                        System.err.println("Unknown argument '" + args[i] + "'.");
                        return;
                    }

                    parameterParametersFile++;
                    parameterScriptFile++;
                    i++;
                }
            }

            if (args.length < parameterScriptFile + 1) {
                System.out.println("OpenCMIS Script Executor\n");
                System.out.println("Usage: [-u] [-p] [-v] <path-to-session-parameters-file> <path-to-groovy-script-file>\n");
                System.out.println("Parameters:\n");
                System.out.println(" -u                                 ask for username");
                System.out.println(" -p                                 ask for password");
                System.out.println(" -v                                 verbose output");
                System.out.println(" <path-to-session-parameters-file>  "
                        + "path of the properties file that contains the session parameters");
                System.out.println(" <path-to-groovy-script-file>       path of the groovy script file");
                return;
            }

            // check parameter file
            File parametersFile = new File(args[parameterParametersFile]);
            if (!parametersFile.exists()) {
                System.err.println("Session parameters file does not exist: " + parametersFile.getAbsolutePath());
                return;
            }
            if (!parametersFile.isFile()) {
                System.err.println("Session parameters file is not a file: " + parametersFile.getAbsolutePath());
                return;
            }

            // check script file
            File scriptFile = new File(args[parameterScriptFile]);
            if (!scriptFile.exists()) {
                System.err.println("Script file does not exist: " + scriptFile.getAbsolutePath());
                return;
            }
            if (!scriptFile.isFile()) {
                System.err.println("Script file is not a file: " + scriptFile.getAbsolutePath());
                return;
            }

            // read user
            String username = null;
            if (readUser) {
                username = readLine();
                if (username == null) {
                    System.err.println("Please enter a username!");
                    return;
                }
            }

            // read password
            char[] password = null;
            if (readPassword) {
                password = readPassword();
                if (password == null) {
                    System.err.println("Please enter a password!");
                    return;
                }
            }

            // create session

            if (verbose) {
                System.out.println("\nConnecting...");
            }

            Session session = createSession(parametersFile, username, password);

            // run script, if we have session
            if (session != null) {
                if (verbose) {
                    System.out.println("\nConnected to " + session.getRepositoryInfo().getName() + " ("
                            + session.getRepositoryInfo().getId() + ")\n");
                }

                String[] scriptArgs = new String[args.length - (parameterScriptFile + 1)];
                if (scriptArgs.length > 0) {
                    System.arraycopy(args, parameterScriptFile + 1, scriptArgs, 0, scriptArgs.length);
                }

                if (verbose) {
                    System.out.print("Starting: " + scriptFile.getName());
                    for (String arg : scriptArgs) {
                        System.out.print(" " + arg);
                    }
                    System.out.println('\n');
                }

                runGroovyScript(session, scriptFile, scriptArgs);
            } else {
                System.err.println("Session couldn't be created!");
                System.exit(1);
            }

            System.exit(0);
        } catch (Exception ex) {
            System.err.println("Error: " + ex.toString());
            if (verbose) {
                System.err.println("\nStacktrace:");
                ex.printStackTrace(System.err);
            }

            System.exit(1);
        }
    }

    /**
     * Reads a line from the console.
     */
    private static String readLine() {
        Console console = System.console();
        if (console == null) {
            try {
                System.out.print("Username: ");

                char[] line = readFromSystemIn();
                if (line == null) {
                    return null;
                }

                return new String(line);
            } catch (IOException ioe) {
                return null;
            }
        } else {
            return console.readLine("Username: ");
        }
    }

    /**
     * Reads a password from the console.
     */
    private static char[] readPassword() {
        Console console = System.console();
        if (console == null) {
            try {
                System.out.println("WARNING: Password will be echoed on screen!");
                System.out.print("Password: ");

                return readFromSystemIn();
            } catch (IOException ioe) {
                return null;
            }
        } else {
            return console.readPassword("Password: ");
        }
    }

    private static char[] readFromSystemIn() throws IOException {
        InputStreamReader reader = new InputStreamReader(System.in);
        char[] buffer = new char[128];

        int l = reader.read(buffer) - 1;
        if (l > 0) {
            char[] result = new char[l];
            System.arraycopy(buffer, 0, result, 0, l);
            return result;
        } else {
            return null;
        }
    }

    /**
     * Creates an OpenCMIS session from session parameters in a file.
     */
    private static Session createSession(File parametersFile, String username, char[] password) {
        SessionParameterMap parameters = new SessionParameterMap();

        // read session parameters
        try {
            parameters.load(parametersFile);
        } catch (IOException ioe) {
            System.err.println("Cannot read session parameters file: " + ioe.toString());
            return null;
        }

        // set user
        if (username != null) {
            parameters.put(SessionParameter.USER, username);
        }

        // set password
        if (password != null) {
            parameters.put(SessionParameter.PASSWORD, new String(password));
        }

        // create session
        SessionFactory factory = SessionFactoryImpl.newInstance();
        Session session = factory.createSession(parameters);

        return session;
    }

    /**
     * Runs a Groovy Script.
     */
    private static void runGroovyScript(Session session, File scriptFile, String[] scriptArgs)
            throws CompilationFailedException, IOException {
        // create Groovy shell
        Binding binding = new Binding();
        binding.setVariable("session", session);
        binding.setVariable("binding", session.getBinding());
        binding.setVariable("out", System.out);

        GroovyShell shell = new GroovyShell(ScriptExecutor.class.getClassLoader(), binding);

        // ... and execute
        Object result = shell.run(scriptFile, scriptArgs);
        if (result != null) {
            System.out.println(result);
        }
    }
}

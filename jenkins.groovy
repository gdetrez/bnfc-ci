String dir = 'BNFC'
folder { name "$dir" }

String githubProject = "BNFC/bnfc"

// Base Job
// This represents defaults configuration for most of the other jobs
job {
  name "$dir/_base-job"
  environmentVariables {
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  wrappers { preBuildCleanup {} }
  parameters {
    stringParam("BNFC_VERSION")
    stringParam("SDIST_BUILD_NUMBER")
  }
}


/* ~~~ Commit stage ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
sdistJob = job {
  name "$dir/sdist"
  using "$dir/_base-job"
  scm {
    git {
      remote {
        github githubProject
      }
    }
  }
  steps {
    shell "cd source; cabal sdist"
  }
  publishers {
    archiveArtifacts 'source/dist/BNFC-${BNFC_VERSION}.tar.gz'
  }
}

commitBuildJob = job {
  name "$dir/commit-build"
  using "$dir/_base-job"
  scm {
    git {
      remote {
        github githubProject
        }
    }
  }
  steps {
    shell """\
      cd source
      # Setup sandbox
      cabal sandbox init
      cabal install --enable-tests --only-dependencies
      # Build & unit tests
      cabal configure -v --enable-tests
      cabal build
      cabal test
      """
  }
  publishers {
    warnings(['Glasgow Haskell Compiler'])
  }
}

acceptanceTestsJob = job {
  name "$dir/acceptance-tests"
  using "$dir/_base-job"
  scm {
    git {
      remote {
        github githubProject
      }
    }
  }
  steps {
    copyArtifacts("$dir/sdist", "", "testing/", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell """
      cd testing
      export CLASSPATH=.:\$(pwd)/data/javatools.jar

      # Setup sandbox
      cabal sandbox init
      cabal install --only-dependencies \
        --constraint="HTF==0.11.*" \
        --constraint='aeson==0.7.0.4' \
        --constraint='haskell-src-exts==1.15.*'
      cabal install BNFC-\${BNFC_VERSION}.tar.gz

      # Compile test suite
      cabal configure
      cabal build

      # Run tests
      set +e
      cabal exec cabal -- run -- --xml=bnfc-system-tests.xml
      exit 0
    """
  }
  publishers {
    archiveJunit('testing/bnfc-system-tests.xml', true)
  }
}

testBuildGht742Job = job {
  name "$dir/test-build-ghc-7.4.2"
  using "$dir/_base-job"
  steps{
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/usr/bin/ghc-7.4.2 BNFC-*.tar.gz
    """)
  }
}

testBuildGht783Job = job {
  name "$dir/test-build-ghc-7.8.3"
  using "$dir/_base-job"
  steps{
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/opt/haskell/x86_64/ghc-7.8.3/bin/ghc-7.8.3  BNFC-*.tar.gz
    """)
  }
}


/* ~~~ BINARIES ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
bdistMacJob = job {
  name "$dir/bdist-mac"
  using "$dir/_base-job"
  label "mac"
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell 'tar xf BNFC-${BNFC_VERSION}.tar.gz --strip-components=1'
    shell """
      cabal sandbox init
      cabal configure  --prefix=/usr
      cabal build
      cabal copy --destdir=\$(pwd)/dist/install
      pkgbuild --identifier com.digitalgrammars.bnfc.pkg \
        --version \${BNFC_VERSION} \
        --root \$(pwd)/dist/install/usr/bin \
        --install-location /usr/bin \
        dist/BNFC-\${BNFC_VERSION}-mac.pkg
    """
  }
  publishers {
    archiveArtifacts 'dist/BNFC-${BNFC_VERSION}-mac.pkg'
  }
}

bdistLinux64Job = job {
  name "$dir/bdist-linux64"
  using "$dir/_base-job"
  environmentVariables {
    env('DESTDIR', 'BNFC-${BNFC_VERSION}-linux64')
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell 'tar xf BNFC-${BNFC_VERSION}.tar.gz --strip-components=1'
    shell '''
      OPTS=--with-ghc=/opt/haskell/x86_64/ghc-7.8.3/bin/ghc
      cabal sandbox init
      cabal ${OPTS} install --only-dependencies
      cabal ${OPTS} configure --prefix=/
      cabal ${OPTS} build
      cabal copy --destdir=dist/install
      cp dist/install/bin/bnfc ${DESTDIR}
      cp LICENSE ${DESTDIR}
    '''
    shell 'tar -cvz ${DESTDIR} > ${DESTDIR}.tar.gz'
  }
  publishers {
    archiveArtifacts '${DESTDIR}.tar.gz'
  }
}

bdistLinux32Job = job {
  name "$dir/bdist-linux32"
  using "$dir/_base-job"
  environmentVariables {
    env('DESTDIR', 'BNFC-${BNFC_VERSION}-linux32')
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell "tar xf BNFC-\${BNFC_VERSION}.tar.gz --strip-components=1"
    shell '''
      OPTS=--with-ghc=/opt/haskell/i386/ghc-7.8.3/bin/ghc
      OPTS+=" --ghc-option=-optc-m32"
      OPTS+=" --ghc-option=-opta-m32"
      OPTS+=" --ghc-option=-optl-m32"
      OPTS+=" --ld-option=-melf_i386"

      cabal sandbox init
      cabal ${OPTS} install --only-dependencies
      cabal ${OPTS} configure --prefix=/
      cabal ${OPTS} build
      cabal copy --destdir=dist/install
      cp dist/install/bin/bnfc ${DESTDIR}
      cp LICENSE ${DESTDIR}
    '''
    shell 'tar -cvz ${DESTDIR} > ${DESTDIR}.tar.gz'
  }
  publishers {
    archiveArtifacts '${DESTDIR}.tar.gz'
  }
}

bdistMacJob = job {
  name "$dir/bdist-win"
  using "$dir/_base-job"
  label "windows-vm"
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    batchFile '''
      cabal get BNFC-%BNFC_VERSION%.tar.gz
      cd BNFC-%BNFC_VERSION%
      cabal sandbox init;
      cabal install --only-dependencies
      cabal configure
      cabal build
      RENAME dist\build\bnfc\bnfc.exe bnfc-%BNFC_VERSION%-win.exe
    '''
  }
  publishers {
    archiveArtifacts 'BNFC-%BNFC_VERSION%\\dist\\build\\bnfc\\bnfc-%BNFC_VERSION%.exe'
  }
}
/* ~~~ Main pipeline job ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
/* This is an instance of a multi-job that orchestrate the whole pipeline
 * It launch the different jobs in different phases (Commit, QA and binaries)
 * and collect the resulting binaries
 */
job(type: Multijob) {
  name "$dir/ci-pipeline"

  // Where to store the generated artifacts
  String artifactDir = "_artifacts"

  wrappers {
    preBuildCleanup {
      includePattern(artifactDir)
      deleteDirectories()
    }
  }
  scm {
    git {
      remote {
        github githubProject
      }
    }
  }
  triggers {
    githubPush()
  }
  steps {
    // Those two first steps are parsing the numerical version from the cabal
    // file and store it in an environment variable
    shell 'sed -ne "s/^[Vv]ersion: *\\([0-9.]*\\).*/BNFC_VERSION=\\1/p" source/BNFC.cabal > version.properties'
    environmentVariables {
      propertiesFile('version.properties')
    }
    phase() {
      phaseName 'Commit'
      job(commitBuildJob.name) {
        gitRevision()
        fileParam('version.properties')
      }
      job(sdistJob.name) {
        gitRevision()
        fileParam('version.properties')
      }
    }
    phase() {
      phaseName 'QA'
      job(acceptanceTestsJob.name) {
        gitRevision()
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
      job(testBuildGht742Job.name) {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
      job(testBuildGht783Job.name) {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
    }
    phase() {
      phaseName 'Binaries'
      job(bdistMacJob.name) {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
      job(bdistLinux32Job.name) {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
      job(bdistLinux64Job.name) {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
    }
    copyArtifacts(sdistJob.name, "", artifactDir, flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    copyArtifacts(bdistMacJob.name,"",artifactDir, flatterFiles = true) {
      buildNumber('$BDIST_MAC_BUILD_NUMBER')
    }
    copyArtifacts(bdistLinux32Job.name,"",artifactDir, flatterFiles = true) {
      buildNumber('$BDIST_LINUX32_BUILD_NUMBER')
    }
    copyArtifacts(bdistLinux64Job.name,"",artifactDir, flatterFiles = true) {
      buildNumber('$BDIST_LINUX64_BUILD_NUMBER')
    }
  }
  publishers {
    archiveArtifacts "$artifactDir/*"
  }
}

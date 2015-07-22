String dir = 'BNFC'
folder("$dir") {}

String githubProject = "BNFC/bnfc"

// Base Job
// This represents defaults configuration for most of the other jobs
freeStyleJob("$dir/_base-job") {
  environmentVariables {
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  wrappers { preBuildCleanup {} }
  parameters {
    stringParam("BNFC_VERSION")
    stringParam("BNFC_BUILD_BUILD_NUMBER")
  }
}


/* ~~~ Commit stage ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
commitBuildJob = freeStyleJob("$dir/bnfc-build") {
  previousNames "$dir/commit-build"
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
      cabal sdist
      """
  }
  publishers {
    warnings(['Glasgow Haskell Compiler'])
    tasks('**/*.hs', '', high = 'FIXME', normal = 'TODO', low = '')
    archiveArtifacts 'source/dist/BNFC-${BNFC_VERSION}.tar.gz'
  }
}

acceptanceTestsJob = freeStyleJob("$dir/bnfc-system-tests") {
  previousNames "$dir/acceptance-tests"
  using "$dir/_base-job"
  scm {
    git {
      remote {
        github githubProject
      }
    }
  }
  steps {
    copyArtifacts(commitBuildJob.name, "", "testing/", flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
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
      cabal exec cabal -- run -- --xml=bnfc-system-tests.xml -j2
      exit 0
    """
  }
  publishers {
    archiveJunit('testing/bnfc-system-tests.xml') {
      retainLongStdout true
    }
  }
}

testInstallEnableTestsJob = freeStyleJob("$dir/bnfc-test-install-enable-tests") {
  using "$dir/_base-job"
  steps{
    copyArtifacts(commitBuildJob.name, "", flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    shell("""
      cabal sandbox init
      cabal -v install --enable-tests BNFC-*.tar.gz
    """)
  }
}

testInstallJob = matrixJob("$dir/bnfc-install-tests") {
  axes {
    text("GHC_VERSION", "7.4.1", "7.4.2", "7.6.1", "7.6.2", "7.6.3", "7.8.1", "7.8.3", "7.8.4", "7.10.1")
  }
  environmentVariables {
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  wrappers { preBuildCleanup {} }
  parameters {
    stringParam("BNFC_VERSION")
    stringParam("BNFC_BUILD_BUILD_NUMBER")
  }
  steps {
    copyArtifacts(commitBuildJob.name, '', true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    shell """
      cabal sandbox init
      cabal -v install BNFC-*.tar.gz \
        --with-compiler=/srv/ghc/x86_64/ghc-\${GHC_VERSION}/bin/ghc-\${GHC_VERSION}
    """
  }
  publishers {
    warnings([], ['Glasgow Haskell Compiler': '.cabal-sandbox/logs/BNFC-${BNFC_VERSION}.log'])
  }
}

/* ~~~ BINARIES ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
bdistMacJob = freeStyleJob("$dir/bnfc-bdist-mac") {
  previousNames "$dir/bdist-mac"
  using "$dir/_base-job"
  label "mac"
  steps {
    copyArtifacts(commitBuildJob.name, "", flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
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

bdistLinuxJob = matrixJob("$dir/bnfc-bdist-linux") {
  axes {
    text("BDIST_ARCH", "linux32", "linux64")
  }
  environmentVariables {
    env('PATH', '$HOME/.cabal/bin:$PATH')
    env('DEST', 'BNFC-${BNFC_VERSION}-${BDIST_ARCH}')
  }
  wrappers { preBuildCleanup {} }
  parameters {
    stringParam("BNFC_VERSION")
    stringParam("BNFC_BUILD_BUILD_NUMBER")
  }
  steps {
    copyArtifacts(commitBuildJob.name, '', true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    shell 'tar xf BNFC-${BNFC_VERSION}.tar.gz --strip-components=1'
    shell 'cabal sandbox init'
    shell '''
      if [ "$BDIST_ARCH" = "linux32" ]
      then
        OPTS=--with-ghc=/opt/haskell/i386/ghc-7.8.3/bin/ghc
        OPTS+=" --ghc-option=-optc-m32"
        OPTS+=" --ghc-option=-opta-m32"
        OPTS+=" --ghc-option=-optl-m32"
        OPTS+=" --ld-option=-melf_i386"
      else
        OPTS=""
      fi
      make bdist BDIST_TAG=${DEST} CABAL_OPTS="${OPTS}"
    '''
  }
  publishers {
    archiveArtifacts 'dist/${DEST}.tar.gz'
  }
}

bdistLinux64Job = freeStyleJob("$dir/bnfc-bdist-linux64") {
  previousNames "$dir/bdist-linux64"
  using "$dir/_base-job"
  environmentVariables {
    env('DEST', 'BNFC-${BNFC_VERSION}-linux64')
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  steps {
    copyArtifacts(commitBuildJob.name, "", flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    shell 'tar xf BNFC-${BNFC_VERSION}.tar.gz --strip-components=1'
    shell 'cabal sandbox init'
    shell 'make bdist BDIST_TAG=${DEST}'
  }
  publishers {
    archiveArtifacts 'dist/${DEST}.tar.gz'
  }
}

bdistLinux32Job = freeStyleJob("$dir/bnfc-bdist-linux32") {
  previousNames "$dir/bdist-linux32"
  using "$dir/_base-job"
  environmentVariables {
    env('DEST', 'BNFC-${BNFC_VERSION}-linux32')
    env('PATH', '$HOME/.cabal/bin:$PATH')
  }
  steps {
    copyArtifacts(commitBuildJob.name, "", flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    shell "tar xf BNFC-\${BNFC_VERSION}.tar.gz --strip-components=1"
    shell 'cabal sandbox init'
    shell '''
      OPTS=--with-ghc=/opt/haskell/i386/ghc-7.8.3/bin/ghc
      OPTS+=" --ghc-option=-optc-m32"
      OPTS+=" --ghc-option=-opta-m32"
      OPTS+=" --ghc-option=-optl-m32"
      OPTS+=" --ld-option=-melf_i386"
      make bdist BDIST_TAG=${DEST} CABAL_OPTS=${OPTS}
    '''
  }
  publishers {
    archiveArtifacts 'dist/${DEST}.tar.gz'
  }
}

bdistWinJob = freeStyleJob("$dir/bnfc-bdist-win") {
  previousNames "$dir/bdist-win"
  using "$dir/_base-job"
  label "windows-vm"
  steps {
    copyArtifacts(commitBuildJob.name, "", flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    batchFile '''
      cabal get BNFC-%BNFC_VERSION%.tar.gz
      cd BNFC-%BNFC_VERSION%
      cabal sandbox init;
      cabal install --only-dependencies
      cabal configure
      cabal build
      RENAME dist\\build\\bnfc\\bnfc.exe bnfc-%BNFC_VERSION%-win.exe
    '''
  }
  publishers {
    archiveArtifacts 'BNFC-${BNFC_VERSION}\\dist\\build\\bnfc\\bnfc-${BNFC_VERSION}-win.exe'
  }
}
/* ~~~ Main pipeline job ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
/* This is an instance of a multi-job that orchestrate the whole pipeline
 * It launch the different jobs in different phases (Commit, QA and binaries)
 * and collect the resulting binaries
 */
multiJob("$dir/bnfc-pipeline") {
  previousNames "$dir/ci-pipeline"

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
    }
    phase() {
      phaseName 'QA'
      job(acceptanceTestsJob.name) {
        gitRevision()
        fileParam('version.properties')
        prop("BNFC_BUILD_BUILD_NUMBER", '$BNFC_BUILD_BUILD_NUMBER')
      }
      job(testInstallJob.name) {
        fileParam('version.properties')
        prop("BNFC_BUILD_BUILD_NUMBER", '$BNFC_BUILD_BUILD_NUMBER')
      }
      job(testInstallEnableTestsJob.name) {
        fileParam('version.properties')
        prop("BNFC_BUILD_BUILD_NUMBER", '$BNFC_BUILD_BUILD_NUMBER')
      }
    }
    phase() {
      phaseName 'Binaries'
      job(bdistMacJob.name) {
        fileParam('version.properties')
        prop("BNFC_BUILD_BUILD_NUMBER", '$BNFC_BUILD_BUILD_NUMBER')
      }
      job(bdistLinuxJob.name) {
        fileParam('version.properties')
        prop("BNFC_BUILD_BUILD_NUMBER", '$BNFC_BUILD_BUILD_NUMBER')
      }
      //job(bdistWinJob.name) {
      //  fileParam('version.properties')
      //  prop("BNFC_BUILD_BUILD_NUMBER", '$BNFC_BUILD_BUILD_NUMBER')
      //}
    }
    copyArtifacts(commitBuildJob.name, "", artifactDir, flattenFiles=true) {
      buildNumber('$BNFC_BUILD_BUILD_NUMBER')
    }
    // copyArtifacts(bdistMacJob.name,"",artifactDir, flattenFiles = true) {
    //   buildNumber('$BNFC_BDIST_MAC_BUILD_NUMBER')
    // }
    copyArtifacts(bdistLinuxJob.name,"",artifactDir, flattenFiles = true) {
      buildNumber('$BNFC_BDIST_LINUX_BUILD_NUMBER')
    }
    // copyArtifacts(bdistWinJob.name,"",artifactDir, flattenFiles = true) {
    //   buildNumber('$BNFC_BDIST_WIN_BUILD_NUMBER')
    // }
  }
  publishers {
    archiveArtifacts "$artifactDir/**/*"
  }
}

String dir = 'abc'
folder { name "$dir" }

String githubProject = "BNFC/bnfc"

job(type: Multijob) {
  name "$dir/ci-pipeline"
  wrappers {
    preBuildCleanup {
      includePattern("_artifacts")
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
  steps {
    // Those two first steps are parsing the numerical version from the cabal
    // file and store it in an environment variable
    shell 'sed -ne "s/^[Vv]ersion: *\\([0-9.]*\\).*/BNFC_VERSION=\\1/p" source/BNFC.cabal > version.properties'
    environmentVariables {
      propertiesFile('version.properties')
    }
    phase() {
      phaseName 'Commit'
      job("$dir/commit-build") {
        gitRevision()
        fileParam('version.properties')
      }
      job("$dir/sdist") {
        gitRevision()
        fileParam('version.properties')
      }
    }
    phase() {
      phaseName 'QA'
      job("$dir/acceptance-tests") {
        gitRevision()
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
      job("$dir/test-build-ghc-7.4.2") {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
      job("$dir/test-build-ghc-7.8.3") {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
    }
    phase() {
      phaseName 'Binaries'
      job("$dir/bdist-mac") {
        fileParam('version.properties')
        prop("SDIST_BUILD_NUMBER", '$SDIST_BUILD_NUMBER')
      }
    }
    copyArtifacts("$dir/sdist", "", "_artifacts", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
  }
  publishers {
    archiveArtifacts '_artifacts/*'
  }
}


job {
  name "$dir/_base-job"
  environmentVariables(PATH:"\$HOME/.cabal/bin:\$PATH")
  wrappers { preBuildCleanup {} }
  parameters {
    stringParam("BNFC_VERSION")
    stringParam("SDIST_BUILD_NUMBER")
  }
}

job {
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

job {
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

job {
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
}

job {
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

job {
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
job {
  name "$dir/bdist-mac"
  using "$dir/_base-job"
  label "mac"
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell 'tar xf BNFC-${BNFC_VERSION}.tar.gz --strip-components=1'
    shell """
      runhaskell Setup.lhs configure --prefix=/usr
      runhaskell Setup.lhs build
      runhaskell Setup.lhs copy --destdir=\$(pwd)/dist/install
      pkgbuild --identifier com.digitalgrammars.bnfc.pkg \
        --version \${BNFC_VERSION} \
        --root \$(pwd)/dist/install/usr/bin \
        --install-location /usr/bin \
        dist/BNFC-\${BNFC_VERSION}-mac.pkg
    """
  }
  publishers {
    archiveArtifacts 'BNFC-${BNFC_VERSION}-mac.pkg'
  }
}

job {
  name "$dir/bdist-linux64"
  using "$dir/_base-job"
  environmentVariables(DESTDIR: "BNFC-\$BNFC_VERSION-linux64")
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) {
      buildNumber('$SDIST_BUILD_NUMBER')
    }
    shell "tar xf BNFC-\${BNFC_VERSION}.tar.gz --strip-components=1"
    shell """
      cabal sandbox init
      cabal sandbox install --only-dependencies
      cabal configure --prefix=/
      cabal build
      cabal copy --destdir=\${DESTDIR}
    """
    shell 'tar -cvz ${DESTDIR} ${DESTDIR}.tar.gz'
  }
  publishers {
    archiveArtifacts '${DESTDIR}.tar.gz'
  }
}

String dir = 'abc'
folder { name "$dir" }

String githubProject = "BNFC/bnfc"

job(type: Multijob) {
  name "$dir/ci-pipeline"
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
}


job {
  name "$dir/_base-job"
  environmentVariables(PATH:"\$HOME/.cabal/bin:\$PATH")
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
  wrappers { preBuildCleanup {} }
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
  wrappers { preBuildCleanup {} }
  steps {
    copyArtifacts("$dir/sdist", "", "testing/", flattenFiles=true) {
      latestSuccessful()
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

def Closure copySdist(target = '') {
   copyArtifacts("$dir/sdist", target, flattenFiles=true) {
    buildNumber('$SDIST_BUILD_NUMBER')
  }
}

job {
  name "$dir/test-build-ghc-7.4.2"
  using "$dir/_base-job"
  wrappers { preBuildCleanup {} }
  steps{
    copySdist()
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/usr/bin/ghc-7.4.2 BNFC-*.tar.gz
    """)
  }
}

job {
  name "$dir/test-build-ghc-7.8.3"
  using "$dir/_base-job"
  wrappers { preBuildCleanup() }
  steps{
    copyArtifacts("$dir/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/opt/haskell/x86_64/ghc-7.8.3/bin/ghc-7.8.3  BNFC-*.tar.gz
    """)
  }
}

job {
  name "$dir/bdist-mac"
  using "$dir/_base-job"
  wrappers { preBuildCleanup {} }
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell """
      tar xf BNFC-\${BNFC_VERSION}.tar.gz
      cd BNFC-\${BNFC_VERSION}
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
}

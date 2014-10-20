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
    shell 'sed -ne "s/^version: *\\([0-9.]*\\).*/BNFC_VERSION=\\1/p" source/BNFC.cabal > version.properties'
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
      }
      job("$dir/test-build-ghc-7.4.2") {
        fileParam('version.properties')
      }
      job("$dir/test-build-ghc-7.8.3") {
        fileParam('version.properties')
      }
    }
    phase() {
      phaseName 'Binaries'
    }
  }
}

job {
  name "$dir/sdist"
  parameters {
    stringParam("BNFC_VERSION")
  }
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
    archiveArtifacts 'source/dist/BNFC-*.tar.gz'
  }
}

job {
  name "$dir/commit-build"
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
  scm {
    git {
      remote {
        github githubProject
      }
    }
  }
  wrappers { preBuildCleanup {} }
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell """
      cd testing
      export CLASSPATH=.:\$(pwd)/data/javatools.jar

      # Setup sandbox
      cabal sandbox init
      cabal install --only-dependencies \
        --constraint="HTF==0.11.*" \
        --constraint='aeson==0.7.0.4' \
        --constraint='haskell-src-exts==1.15.*'
      cabal install BNFC-*.tar.gz

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
  wrappers { preBuildCleanup {} }
  steps{
    copyArtifacts("$dir/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/usr/bin/ghc-7.4.2 BNFC-*.tar.gz
    """)
  }
}

job {
  name "$dir/test-build-ghc-7.8.3"
  wrappers { preBuildCleanup {} }
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
  wrappers { preBuildCleanup {} }
  steps {
    copyArtifacts("$dir/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell """
      SRCTGZ=\$(echo BNFC-*.tar.gz)
      tar xf \${SRCTGZ}
      cd \${SRCTGZ%.tar.gz}
      BNFC_VERSION=\$(sed -n 's/^Version:[ \\t]*\\(.*\\)\$/\\1/p' BNFC.cabal)
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

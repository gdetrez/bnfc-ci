folder { name 'abc' }

String githubProject = "BNFC/bnfc"

job(type: Multijob) {
  name "abc/ci-pipeline"
  steps {
    phase() {
      phaseName 'Commit'
      job("abc/commit-build") { gitRevision() }
      job("abc/sdist") { gitRevision() }
    }
    phase() {
      phaseName 'QA'
      job("abc/acceptance-tests") { gitRevision() }
      job("abc/test-build-ghc-7.4.2")
      job("abc/test-build-ghc-7.8.3")
    }
    phase() {
      phaseName 'Binaries'
    }
  }
}

job {
  name 'abc/sdist'
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
  name "abc/commit-build"
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
  name "abc/acceptance-tests"
  scm {
    git {
      remote {
        github githubProject
      }
    }
  }
  wrappers { preBuildCleanup {} }
  steps {
    copyArtifacts("abc/sdist", "", flattenFiles=true) { latestSuccessful() }
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
  name "abc/test-build-ghc-7.4.2"
  wrappers { preBuildCleanup {} }
  steps{
    copyArtifacts("abc/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/usr/bin/ghc-7.4.2 BNFC-*.tar.gz
    """)
  }
}

job {
  name "abc/test-build-ghc-7.8.3"
  wrappers { preBuildCleanup {} }
  steps{
    copyArtifacts("abc/sdist", "", flattenFiles=true) { latestSuccessful() }
    shell("""
      cabal sandbox init
      cabal -v install --with-compiler=/opt/haskell/x86_64/ghc-7.8.3/bin/ghc-7.8.3  BNFC-*.tar.gz
    """)
  }
}

#!/bin/bash
set -e
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"

case "${1:-run}" in
  run)
    mvn compile -q exec:java -Dexec.mainClass="io.crashnotifier.OperatorApplication"
    ;;
  crd)
    mvn compile -q
    kubectl apply -f target/classes/META-INF/fabric8/podwatchers.crash-notifier.io-v1.yml
    ;;
  test)
    mvn test
    ;;
  compile)
    mvn compile
    ;;
  *)
    echo "Usage: ./run.sh [run|crd|test|compile]"
    ;;
esac

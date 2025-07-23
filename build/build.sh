#!/bin/bash
# Maven Tools MCP Server - Unix/Linux Build Helper
# Cross-platform equivalent of build.cmd
# Handles common build tasks with user-friendly interface

set -e

# Function to show JAR build results
show_jar_result() {
    echo "✅ Command completed successfully!"
    
    # Look for the JAR file
    JAR_FILE=$(find ../target -name "*.jar" -not -name "*.original" 2>/dev/null | head -1)
    
    if [ -n "$JAR_FILE" ] && [ -f "$JAR_FILE" ]; then
        echo ""
        echo "JAR file created: $JAR_FILE"
        echo "To run: java -jar $JAR_FILE"
    fi
}

# Check for command line argument (for CI use)
if [ "$1" != "" ]; then
    choice="$1"
    echo "Running in non-interactive mode with option: $choice"
else
    # Interactive mode
    echo "Maven Tools MCP Server - Unix Build Helper"
    echo "==========================================="

    # Check if we're in the right directory
    if [ ! -f "../mvnw" ]; then
        echo "❌ This script must be run from the build/ directory in the maven-tools-mcp project"
        echo "Current directory: $(pwd)"
        exit 1
    fi

    echo ""
    echo "Available commands:"
    echo "1. Build JAR (skip tests)"
    echo "2. Build JAR (with tests)"
    echo "3. Build Native Docker image (slow, optimized)"
    echo "4. Build JVM Docker image (faster build)"
    echo "5. Clean build artifacts"
    echo "6. Run tests only"
    echo ""

    read -p "Choose option (1-6): " choice
fi

# Make mvnw executable if it isn't already
chmod +x ../mvnw

case $choice in
    1)
        echo "📦 Building JAR without tests..."
        (cd .. && ./mvnw clean package -DskipTests)
        show_jar_result
        ;;
    2)
        echo "📦 Building JAR with tests..."
        (cd .. && ./mvnw clean package)
        show_jar_result
        ;;
    3)
        echo "🐳 Building Native Docker image with buildpacks..."
        echo "⏳ This may take 10-15 minutes for native compilation..."
        echo "Step 1: Package application..."
        (cd .. && ./mvnw clean package -DskipTests)
        
        echo "Step 2: Build Native Docker image..."
        (cd .. && ./mvnw -Pnative spring-boot:build-image)
        
        # Get project version for image name
        PROJECT_VERSION=$(cd .. && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.1.0")
        
        echo "✅ Native Docker image built successfully!"
        echo ""
        echo "To run: docker run -i -e SPRING_PROFILES_ACTIVE=docker arvindand/maven-tools-mcp:${PROJECT_VERSION}"
        exit 0
        ;;
    4)
        echo "🐳 Building JVM Docker image with buildpacks..."
        echo "Step 1: Package application..."
        (cd .. && ./mvnw clean package -DskipTests)
        
        echo "Step 2: Build JVM Docker image..."
        (cd .. && ./mvnw spring-boot:build-image)
        
        # Get project version for image name
        PROJECT_VERSION=$(cd .. && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.1.0")
        
        echo "✅ JVM Docker image built successfully!"
        echo ""
        echo "To run: docker run -i -e SPRING_PROFILES_ACTIVE=docker arvindand/maven-tools-mcp:${PROJECT_VERSION}"
        exit 0
        ;;
    5)
        echo "🧹 Cleaning build artifacts..."
        (cd .. && ./mvnw clean)
        echo "✅ Clean completed successfully!"
        ;;
    6)
        echo "🧪 Running tests..."
        (cd .. && ./mvnw test)
        echo "✅ Tests completed successfully!"
        ;;
    *)
        echo "❌ Invalid option. Please choose 1-6."
        exit 1
        ;;
esac

exit 0

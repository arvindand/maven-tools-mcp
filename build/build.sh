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
    echo "3. Build Docker image with buildpacks"
    echo "4. Clean build artifacts"
    echo "5. Run tests only"
    echo ""

    read -p "Choose option (1-5): " choice
fi

# Make mvnw executable if it isn't already
chmod +x ../mvnw

case $choice in
    1)
        echo "📦 Building JAR without tests..."
        ../mvnw clean package -DskipTests
        show_jar_result
        ;;
    2)
        echo "📦 Building JAR with tests..."
        ../mvnw clean package
        show_jar_result
        ;;
    3)
        echo "🐳 Building Docker image with buildpacks..."
        echo "Step 1: Package application..."
        ../mvnw clean package -DskipTests
        
        echo "Step 2: Build Docker image..."
        ../mvnw spring-boot:build-image
        
        # Get project version for image name
        PROJECT_VERSION=$(../mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "0.1.2-SNAPSHOT")
        
        echo "✅ Docker image built successfully!"
        echo ""
        echo "To run: docker run -i maven-tools-mcp:${PROJECT_VERSION}"
        exit 0
        ;;
    4)
        echo "🧹 Cleaning build artifacts..."
        ../mvnw clean
        echo "✅ Clean completed successfully!"
        ;;
    5)
        echo "🧪 Running tests..."
        ../mvnw test
        echo "✅ Tests completed successfully!"
        ;;
    *)
        echo "❌ Invalid option. Please choose 1-5."
        exit 1
        ;;
esac

exit 0

# Function definition moved to top of file for better organization

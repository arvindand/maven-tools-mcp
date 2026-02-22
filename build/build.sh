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
    echo "7. Build Native Docker image WITHOUT Context7"
    echo "8. Build Native Docker image with HTTP transport"
    echo ""

    read -p "Choose option (1-8): " choice
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
        echo "🐳 Building Native Docker images with buildpacks..."
        echo "⏳ This may take 20-25 minutes for native compilation (building 2 images)..."
        echo "Step 1: Package application..."
        (cd .. && ./mvnw clean package -DskipTests)
        
        # Get project version for image name
        PROJECT_VERSION=$(cd .. && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "2.0.4")
        
        echo ""
        echo "Step 2: Build Native Docker image WITH Context7..."
        (cd .. && SPRING_PROFILES_ACTIVE=docker ./mvnw -Pnative spring-boot:build-image \
          -Dspring-boot.build-image.imageName=maven-tools-mcp:${PROJECT_VERSION})
        
        echo ""
        echo "Step 3: Build Native Docker image WITHOUT Context7..."
        (cd .. && SPRING_PROFILES_ACTIVE=docker,no-context7 ./mvnw -Pnative spring-boot:build-image \
          -Dspring-boot.build-image.imageName=maven-tools-mcp:${PROJECT_VERSION}-noc7)
        
        echo ""
        echo "✅ Native Docker images built successfully!"
        echo ""
        echo "Two images created:"
        echo "  1. maven-tools-mcp:${PROJECT_VERSION} (with Context7)"
        echo "  2. maven-tools-mcp:${PROJECT_VERSION}-noc7 (without Context7)"
        echo ""
        echo "🚀 Run with Context7 enabled:"
        echo "   docker run -i maven-tools-mcp:${PROJECT_VERSION}"
        echo ""
        echo "🚀 Run without Context7:"
        echo "   docker run -i maven-tools-mcp:${PROJECT_VERSION}-noc7"
        exit 0
        ;;
    4)
        echo "🐳 Building JVM Docker image with buildpacks..."
        echo "Step 1: Package application..."
        (cd .. && ./mvnw clean package -DskipTests)
        
        echo "Step 2: Build JVM Docker image..."
        (cd .. && ./mvnw spring-boot:build-image)
        
        # Get project version for image name
        PROJECT_VERSION=$(cd .. && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "2.0.4")
        
        echo "✅ JVM Docker image built successfully: maven-tools-mcp:${PROJECT_VERSION}"
        echo ""
        echo "🚀 Run with Context7 enabled (default):"
        echo "   docker run -i -e SPRING_PROFILES_ACTIVE=docker maven-tools-mcp:${PROJECT_VERSION}"
        echo ""
        echo "🚀 Run with Context7 disabled:"
        echo "   docker run -i -e SPRING_PROFILES_ACTIVE=docker \\"
        echo "     -e SPRING_AI_MCP_CLIENT_ENABLED=false \\"
        echo "     -e SPRING_AI_MCP_CLIENT_TOOLCALLBACK_ENABLED=false \\"
        echo "     -e CONTEXT7_ENABLED=false \\"
        echo "     maven-tools-mcp:${PROJECT_VERSION}"
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
    7)
        echo "🐳 Building Native Docker image WITHOUT Context7..."
        echo "⏳ This may take 10-15 minutes for native compilation..."
        echo "Step 1: Package application..."
        (cd .. && ./mvnw clean package -DskipTests)
        
        # Get project version for image name
        PROJECT_VERSION=$(cd .. && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "2.0.4")
        
        echo ""
        echo "Step 2: Build Native Docker image with no-context7 profile..."
        (cd .. && SPRING_PROFILES_ACTIVE=docker,no-context7 ./mvnw -Pnative spring-boot:build-image \
          -Dspring-boot.build-image.imageName=maven-tools-mcp:${PROJECT_VERSION}-noc7)
        
        echo ""
        echo "✅ Native Docker image built successfully!"
        echo ""
        echo "Image created: maven-tools-mcp:${PROJECT_VERSION}-noc7"
        echo ""
        echo "🚀 Run with:"
        echo "   docker run -i maven-tools-mcp:${PROJECT_VERSION}-noc7"
        exit 0
        ;;
    8)
        echo "🐳 Building Native Docker image with HTTP transport..."
        echo "⏳ This may take 10-15 minutes for native compilation..."
        echo "Step 1: Package application..."
        (cd .. && ./mvnw clean package -DskipTests)

        # Get project version for image name
        PROJECT_VERSION=$(cd .. && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "2.0.4")

        echo ""
        echo "Step 2: Build Native Docker image with http profile..."
        (cd .. && SPRING_PROFILES_ACTIVE=http ./mvnw -Pnative spring-boot:build-image \
          -Dspring-boot.build-image.imageName=maven-tools-mcp:${PROJECT_VERSION}-http)

        echo ""
        echo "✅ Native Docker image with HTTP transport built successfully!"
        echo ""
        echo "Image created: maven-tools-mcp:${PROJECT_VERSION}-http"
        echo ""
        echo "🚀 Run with:"
        echo "   docker run -p 8080:8080 maven-tools-mcp:${PROJECT_VERSION}-http"
        echo ""
        echo "📡 Connect via HTTP:"
        echo "   curl -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -d '{...}'"
        exit 0
        ;;
    *)
        echo "❌ Invalid option. Please choose 1-8."
        exit 1
        ;;
esac

exit 0

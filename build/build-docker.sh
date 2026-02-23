#!/bin/bash

# Maven Tools MCP Server - Simplified Docker Build Script
# Focuses on buildpacks with Docker Hub fallback

set -e

# Check for command line argument (for CI use)
if [ "$1" != "" ]; then
    choice="$1"
    echo "Running in non-interactive mode with option: $choice"
else
    # Interactive mode
    echo "Maven Tools MCP Server - Docker Build Options"
    echo "=============================================="

    # Check if Docker is available
    if ! command -v docker &> /dev/null; then
        echo "❌ Docker is not installed or not in PATH"
        exit 1
    fi

    echo ""
    echo "Available options:"
    echo "1. Use pre-built native image from Docker Hub (fastest, recommended)"
    echo "2. Build locally with Spring Boot buildpacks - Native Image (requires Maven + time)"
    echo "3. Build locally with Spring Boot buildpacks - JVM Image (faster build)"
    echo ""

    read -p "Choose option (1-3): " choice
fi

case $choice in
    1)
        echo "🐳 Pulling pre-built native images from Docker Hub..."
        
        # Pull both variants
        echo "Pulling image WITH Context7..."
        docker pull arvindand/maven-tools-mcp:latest
        
        echo "Pulling image WITHOUT Context7 (noc7)..."
        docker pull arvindand/maven-tools-mcp:latest-noc7
        
        echo ""
        echo "✅ Two images are now available:"
        echo "  1. With Context7:    docker run -i arvindand/maven-tools-mcp:latest"
        echo "  2. Without Context7: docker run -i arvindand/maven-tools-mcp:latest-noc7"
        ;;
    2)
        echo "🏗️  Building Native Images with Spring Boot buildpacks..."
        echo "This creates highly optimized native executables but takes longer to build"
        echo "Building TWO images: with and without Context7"
        
        # Check if Maven wrapper is available
        if [ ! -f "../mvnw" ]; then
            echo "❌ Maven wrapper not found. Run this script from the build/ directory."
            exit 1
        fi
        
        # Make mvnw executable if it isn't already
        chmod +x ../mvnw
        
        echo "📦 Running Maven package (skipping tests for faster build)..."
        ../mvnw clean package -DskipTests
        
        PROJECT_VERSION=$(../mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "2.0.5")
        
        echo ""
        echo "🐳 Building native image WITH Context7..."
        (cd .. && SPRING_PROFILES_ACTIVE=docker ./mvnw -Pnative spring-boot:build-image \
          -Dspring-boot.build-image.imageName=maven-tools-mcp:${PROJECT_VERSION})
        
        echo ""
        echo "🐳 Building native image WITHOUT Context7 (noc7)..."
        (cd .. && SPRING_PROFILES_ACTIVE=docker,no-context7 ./mvnw -Pnative spring-boot:build-image \
          -Dspring-boot.build-image.imageName=maven-tools-mcp:${PROJECT_VERSION}-noc7)
        
        echo ""
        echo "✅ Built TWO native images:"
        echo "  1. maven-tools-mcp:${PROJECT_VERSION} (with Context7)"
        echo "  2. maven-tools-mcp:${PROJECT_VERSION}-noc7 (without Context7)"
        echo ""
        echo "🚀 Run with Context7:"
        echo "   docker run -i maven-tools-mcp:${PROJECT_VERSION}"
        echo ""
        echo "🚀 Run without Context7:"
        echo "   docker run -i maven-tools-mcp:${PROJECT_VERSION}-noc7"
        ;;
    3)
        echo "🏗️  Building JVM Image with Spring Boot buildpacks..."
        echo "This creates optimized, layered JVM images using Cloud Native Buildpacks"
        
        # Check if Maven wrapper is available
        if [ ! -f "../mvnw" ]; then
            echo "❌ Maven wrapper not found. Run this script from the build/ directory."
            exit 1
        fi
        
        # Make mvnw executable if it isn't already
        chmod +x ../mvnw
        
        echo "📦 Running Maven package (skipping tests for faster build)..."
        ../mvnw clean package -DskipTests
        
        echo "🐳 Building JVM Docker image with buildpacks..."
        (cd .. && SPRING_PROFILES_ACTIVE=docker ./mvnw spring-boot:build-image)
        
        PROJECT_VERSION=$(../mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "2.0.5")
        echo ""
        echo "✅ Built JVM image: maven-tools-mcp:${PROJECT_VERSION}"
        echo ""
        echo "🚀 Run with Context7 (default):"
        echo "   docker run -i maven-tools-mcp:${PROJECT_VERSION}"
        echo ""
        echo "🚀 Run without Context7 (use env vars):"
        echo "   docker run -i -e SPRING_AI_MCP_CLIENT_ENABLED=false \\"
        echo "     -e CONTEXT7_ENABLED=false \\"
        echo "     maven-tools-mcp:${PROJECT_VERSION}"
        ;;
    *)
        echo "❌ Invalid option. Please choose 1, 2, or 3."
        exit 1
        ;;
esac

echo ""
echo "🎉 Docker setup complete!"

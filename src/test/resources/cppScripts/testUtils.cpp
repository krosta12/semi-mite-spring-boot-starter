#include <string>
#include <cmath>

// @mite
int add(int a, int b)
{
    return a + b;
}

// @mite
double multiply(double a, double b)
{
    return a * b;
}

// @mite
bool isPositive(int n)
{
    return n > 0;
}

// @mite
const char *greet(const char *name)
{
    static std::string result;
    result = std::string("Hello, ") + name + "!";
    return result.c_str();
}

int square(int n)
{
    return n * n;
}

// @mite
int sumOfSquares(int a, int b)
{
    return square(a) + square(b);
}

// @mite
double hypotenuse(double a, double b)
{
    return sqrt(a * a + b * b);
}

// @mite
int factorial(int n)
{
    if (n <= 1)
        return 1;
    return n * factorial(n - 1);
}

// @mite
const char *emptyString(const char *s)
{
    static std::string result;
    result = s;
    return result.c_str();
}